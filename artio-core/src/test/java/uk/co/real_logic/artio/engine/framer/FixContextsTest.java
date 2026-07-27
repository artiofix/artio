/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.FileSystemCorruptionException;
import uk.co.real_logic.artio.builder.LogonEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.fixt.FixDictionaryImpl;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INITIAL_SEQUENCE_INDEX;
import static uk.co.real_logic.artio.engine.framer.FixContexts.DUPLICATE_SESSION;
import static uk.co.real_logic.artio.engine.framer.IncrementalSessionIdGenerator.LOWEST_VALID_SESSION_ID;

public class FixContextsTest
{
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int TEST_INITIAL_SEQUENCE = 721;

    private final long time = System.currentTimeMillis();
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private final AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private final MappedFile mappedFile = mock(MappedFile.class);
    private final SessionIdStrategy idStrategy = SessionIdStrategy.senderAndTarget();
    private FixContexts fixContexts = newSessionContexts(buffer);
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private final LogonEncoder logonEncoder = new LogonEncoder();
    private final FixDictionary fixDictionary = FixDictionary.of(FixDictionary.findDefault());

    private final CompositeKey aSession = idStrategy.onInitiateLogon("a", null, null, "b", null, null);
    private final CompositeKey bSession = idStrategy.onInitiateLogon("b", null, null, "a", null, null);
    private final CompositeKey cSession = idStrategy.onInitiateLogon("c", null, null, "c", null, null);
    private final CompositeKey otherSession = idStrategy.onInitiateLogon(
        "acceptor", null, null, "initiator", null, null);

    @Test
    public void sessionContextsAreUnique()
    {
        assertNotEquals(fixContexts.onLogon(aSession, fixDictionary),
            fixContexts.onLogon(bSession, fixDictionary));
    }

    @Test
    public void findsDuplicateSessions()
    {
        fixContexts.onLogon(aSession, fixDictionary);

        assertEquals(FixContexts.DUPLICATE_SESSION, fixContexts.onLogon(aSession, fixDictionary));
    }

    @Test
    public void handsOutSameSessionContextAfterDisconnect()
    {
        final SessionContext sessionContext = fixContexts.onLogon(aSession, fixDictionary);
        fixContexts.onDisconnect(sessionContext.sessionId());

        assertValuesEqual(sessionContext, fixContexts.onLogon(aSession, fixDictionary));
    }

    @Test
    public void persistsSessionContextsOverARestart()
    {
        final SessionContext bContext = fixContexts.onLogon(bSession, fixDictionary);
        final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);

        bContext.onSequenceReset(time);
        aContext.onSequenceReset(time);

        final FixContexts fixContextsAfterRestart = newSessionContexts(buffer);
        final SessionContext reloadedAContext = fixContextsAfterRestart.onLogon(aSession, fixDictionary);
        final SessionContext reloadedBContext = fixContextsAfterRestart.onLogon(bSession, fixDictionary);

        assertValuesEqual(aContext, reloadedAContext);
        assertValuesEqual(bContext, reloadedBContext);

        assertEquals(time, reloadedAContext.lastSequenceResetTime());
        assertEquals(time, reloadedBContext.lastSequenceResetTime());
    }

    @Test
    public void sessionPersistedCorrectlyAfterARestart()
    {
        fixContexts.onLogon(aSession, fixDictionary);
        final FixContexts fixContextsAfterRestart = newSessionContexts(buffer);
        final SessionContext reloadedAContext = fixContextsAfterRestart.onLogon(aSession, fixDictionary);
        reloadedAContext.onSequenceReset(time + 1);

        final FixContexts fixContextsAfterSecondRestart = newSessionContexts(buffer);
        final SessionContext reloadedAgainContext = fixContextsAfterSecondRestart.onLogon(aSession, fixDictionary);
        assertEquals(time + 1, reloadedAgainContext.lastSequenceResetTime());
    }

    @Test
    public void continuesIncrementingSessionContextsAfterRestart()
    {
        final SessionContext bContext = fixContexts.onLogon(bSession, fixDictionary);
        final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);

        final FixContexts fixContextsAfterRestart = newSessionContexts(buffer);

        final SessionContext cContext = fixContextsAfterRestart.onLogon(cSession, fixDictionary);
        assertValidSessionId(cContext.sessionId());
        assertNotEquals(aContext, cContext, "C is a duplicate of A");
        assertNotEquals(bContext, cContext, "C is a duplicate of B");
    }

    @Test
    public void initialSequenceIndex()
    {
        final FixContexts contexts = newSessionContexts(buffer, TEST_INITIAL_SEQUENCE);
        final SessionContext context = contexts.onLogon(aSession, fixDictionary);
        context.onLogon(false, time, fixDictionary);
        assertEquals(TEST_INITIAL_SEQUENCE, context.sequenceIndex());
        context.onSequenceReset(time);
        assertEquals(TEST_INITIAL_SEQUENCE + 1, context.sequenceIndex());
    }

    @Test
    public void checksFileCorruption()
    {
        fixContexts.onLogon(bSession, fixDictionary);
        fixContexts.onLogon(aSession, fixDictionary);

        // corrupt buffer
        buffer.putBytes(8, new byte[1024]);

        newSessionContexts(buffer);

        verify(errorHandler).onError(any(FileSystemCorruptionException.class));
    }

    @Test
    public void validateSizeOfBuffer()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            final AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));
            newSessionContexts(buffer);
        });
    }

    @Test
    public void wrapsOverSectorBoundaries()
    {
        final int requiredNumberOfWritesToSpanSector = 45;

        final List<CompositeKey> keys = IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .mapToObj((i) -> idStrategy.onInitiateLogon("b" + i, null, null, "a" + i, null, null))
            .collect(toList());

        final List<SessionContext> contexts = keys
            .stream()
            .map(compositeKey -> fixContexts.onLogon(compositeKey, fixDictionary))
            .peek(sessionContext -> sessionContext.onSequenceReset(time))
            .collect(toList());

        // Test an update of something not at the tail of the buffer.
        final SessionContext firstContext = contexts.get(0);
        firstContext.onSequenceReset(time);

        final FixContexts contextsAfterRestart = newSessionContexts(buffer);
        IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .forEach((i) -> assertValuesEqual(contexts.get(i),
            contextsAfterRestart.onLogon(keys.get(i), fixDictionary)));
    }

    @Test
    public void resetsSessionContexts()
    {
        final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);
        fixContexts.onDisconnect(aContext.sessionId());

        fixContexts.reset(null);

        assertSessionContextsReset(aContext, fixContexts);

        verifyNoBackUp();
    }

    @Test
    public void resetsSessionContextsFile()
    {
        final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);
        fixContexts.onDisconnect(aContext.sessionId());

        fixContexts.reset(null);

        final FixContexts fixContextsAfterRestart = newSessionContexts(buffer);

        assertSessionContextsReset(aContext, fixContextsAfterRestart);

        verifyNoBackUp();
    }

    @Test
    public void copiesOldSessionContextFile() throws IOException
    {
        final File backupLocation = Files.createTempFile("sessionContexts", "tmp").toFile();
        try
        {
            final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);
            fixContexts.onDisconnect(aContext.sessionId());

            final byte[] oldData = new byte[BUFFER_SIZE];
            buffer.getBytes(0, oldData);

            fixContexts.reset(backupLocation);

            verify(mappedFile).transferTo(backupLocation);
        }
        finally
        {
            IoUtil.deleteIfExists(backupLocation);
        }
    }

    @Test
    public void doesNotReuseExistingSessionIdsForDistinctCompositeKeys()
    {
        fixContexts.onLogon(aSession, fixDictionary);
        fixContexts.onLogon(bSession, fixDictionary); // bump counter

        logonWithSenderAndTarget(aSession.localCompId(), aSession.remoteCompId());

        final SessionContext cContext = fixContexts.onLogon(cSession, fixDictionary);

        assertNotEquals(DUPLICATE_SESSION, cContext);
        assertEquals(3, cContext.sessionId());
    }

    @Test
    public void shouldSupportDictionaryUpdatesAndCompaction()
    {
        final FixDictionary fixtDictionary = fixtDictionary();

        final SessionContext aContext = fixContexts.onLogon(aSession, fixDictionary);
        fixContexts.onLogon(bSession, fixDictionary);

        final long sessionIdA = aContext.sessionId();

        fixContexts.onDisconnect(sessionIdA);
        final int filePosition1 = fixContexts.filePosition();

        // Logon with new fix dictionary
        final SessionContext newAContext = fixContexts.onLogon(aSession, fixtDictionary);
        assertEquals(fixtDictionary, newAContext.lastFixDictionary());
        final int filePosition2 = fixContexts.filePosition();
        assertThat(filePosition2, greaterThan(filePosition1));

        // Restart with compaction
        fixContexts = newSessionContexts(buffer);
        final SessionContext reloadedAContext = fixContexts.lookupById(sessionIdA).getValue();
        assertEquals(fixtDictionary.getClass(), reloadedAContext.lastFixDictionary().getClass());
        final int filePosition3 = fixContexts.filePosition();
        assertThat(filePosition3, lessThan(filePosition2));
    }

    @Test
    public void shouldReloadOldFileFormat() throws IOException
    {
        final InputStream oldFile = FixContexts.class.getResourceAsStream("v2-session_id_buffer");
        final int size = EngineConfiguration.DEFAULT_SESSION_ID_BUFFER_SIZE;
        final byte[] data = new byte[size];
        oldFile.read(data);
        final UnsafeBuffer oldBuffer = new UnsafeBuffer(ByteBuffer.wrap(data));

        // Load old buffer
        final FixContexts fixContexts = newSessionContexts(oldBuffer);
        assertThat(fixContexts.allSessions(), hasSize(1));

        // Modify contents of missing field
        final CompositeKey key = fixContexts.allSessions().get(0).sessionKey();
        final FixDictionary fixtDictionary = fixtDictionary();
        final SessionContext context = fixContexts.onLogon(key, fixtDictionary);
        assertEquals(fixtDictionary, context.lastFixDictionary());

        // Check that reloaded information is read
        final FixContexts fixContexts2 = newSessionContexts(oldBuffer);
        assertThat(fixContexts2.allSessions(), hasSize(1));
        final SessionContext newContext = fixContexts2.lookupById(context.sessionId()).getValue();
        assertEquals(fixtDictionary.getClass(), newContext.lastFixDictionary().getClass());
    }

    private FixDictionary fixtDictionary()
    {
        return FixDictionary.of(FixDictionaryImpl.class);
    }

    private void verifyNoBackUp()
    {
        verify(mappedFile, never()).transferTo(any());
    }

    private void assertSessionContextsReset(final SessionContext aContext, final FixContexts fixContexts)
    {
        final SessionContext bContext = fixContexts.onLogon(bSession, fixDictionary);
        final SessionContext newAContext = fixContexts.onLogon(aSession, fixDictionary);
        assertValidSessionId(bContext.sessionId());
        assertValidSessionId(newAContext.sessionId());
        assertEquals(aContext, bContext, "Session Contexts haven't been reset");
        assertNotEquals(aContext, newAContext, "Session Contexts haven't been reset");
    }

    private void assertValidSessionId(final long cId)
    {
        assertThat(cId, greaterThanOrEqualTo(LOWEST_VALID_SESSION_ID));
    }

    private FixContexts newSessionContexts(final AtomicBuffer buffer)
    {
        return newSessionContexts(buffer, DEFAULT_INITIAL_SEQUENCE_INDEX);
    }

    private FixContexts newSessionContexts(final AtomicBuffer buffer, final int initialSequenceIndex)
    {
        when(mappedFile.buffer()).thenReturn(buffer);
        return new FixContexts(mappedFile, idStrategy, initialSequenceIndex, errorHandler, false,
            new IncrementalSessionIdGenerator());
    }

    private void assertValuesEqual(
        final SessionContext sessionContext,
        final SessionContext secondSessionContext)
    {
        assertEquals(sessionContext, secondSessionContext);
        assertEquals(sessionContext.sequenceIndex(), secondSessionContext.sequenceIndex());
    }

    private long logonWithSenderAndTarget(final String senderCompID, final String targetCompID)
    {
        logonEncoder.header()
            .sendingTime(new byte[] {0})
            .senderCompID(senderCompID)
            .targetCompID(targetCompID);
        return logonEncoder.encryptMethod(0).heartBtInt(0).encode(asciiBuffer, 0);
    }
}
