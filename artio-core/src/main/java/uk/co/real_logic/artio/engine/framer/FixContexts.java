/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SectorFramer;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.engine.logger.LoggerUtil;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.storage.messages.SessionIdDecoder;
import uk.co.real_logic.artio.storage.messages.SessionIdEncoder;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;

import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INITIAL_SEQUENCE_INDEX;
import static uk.co.real_logic.artio.engine.SectorFramer.*;
import static uk.co.real_logic.artio.session.SessionIdStrategy.INSUFFICIENT_SPACE;
import static uk.co.real_logic.artio.storage.messages.SessionIdEncoder.BLOCK_LENGTH;

/**
 * Identifies which sessions are currently authenticated.
 * <p>
 * The session ids table is saved into a file. Records are written out using the {@link SessionIdEncoder}
 * and aren't allowed to span sectors. Each sector has a CRC32 checksum and each checksum is updated after writing
 * each session id record.
 */
public class FixContexts implements SessionContexts
{

    static final SessionContext DUPLICATE_SESSION = new SessionContext(
        null,
        -3,
        -3,
        Session.UNKNOWN_TIME,
        Session.UNKNOWN_TIME,
        null,
        OUT_OF_SPACE,
        DEFAULT_INITIAL_SEQUENCE_INDEX,
        null, false);
    static final SessionContext UNKNOWN_SESSION = new SessionContext(
        null,
        Session.UNKNOWN,
        0,
        Session.UNKNOWN_TIME,
        Session.UNKNOWN_TIME,
        null,
        OUT_OF_SPACE,
        DEFAULT_INITIAL_SEQUENCE_INDEX,
        null, false);
    static final int VERSION_WITHOUT_FIX_DICTIONARY = 2;

    private static final int HEADER_SIZE = MessageHeaderDecoder.ENCODED_LENGTH;

    private static final int ENCODING_BUFFER_SIZE = SECTOR_SIZE - CHECKSUM_SIZE;
    private final UnsafeBuffer compositeKeyBuffer = new UnsafeBuffer(new byte[ENCODING_BUFFER_SIZE]);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final SessionIdEncoder sessionIdEncoder = new SessionIdEncoder();
    private final SessionIdDecoder sessionIdDecoder = new SessionIdDecoder();
    private final int actingBlockLength = sessionIdEncoder.sbeBlockLength();
    private final int actingVersion = sessionIdEncoder.sbeSchemaVersion();

    private final LongHashSet currentlyAuthenticatedSessionIds = new LongHashSet();
    private final CopyOnWriteArrayList<SessionInfo> allSessions = new CopyOnWriteArrayList<>();
    private final Map<CompositeKey, SessionContext> compositeToContext = new HashMap<>();

    private final CRC32 crc32 = new CRC32();
    private final SectorFramer sectorFramer;
    private final ByteBuffer byteBuffer;

    private final AtomicBuffer buffer;
    private final boolean reproductionEnabled;
    private final SessionIdStrategy idStrategy;
    private final ErrorHandler errorHandler;
    private final MappedFile mappedFile;
    private final int initialSequenceIndex;
    private final SessionIdGenerator sessionIdGenerator;

    private int filePosition;

    public FixContexts(
        final MappedFile mappedFile,
        final SessionIdStrategy idStrategy,
        final int initialSequenceIndex,
        final ErrorHandler errorHandler,
        final boolean reproductionEnabled,
        final SessionIdGenerator sessionIdGenerator)
    {
        this.mappedFile = mappedFile;
        this.buffer = mappedFile.buffer();
        this.reproductionEnabled = reproductionEnabled;
        this.byteBuffer = this.buffer.byteBuffer();
        sectorFramer = new SectorFramer(buffer.capacity());
        this.idStrategy = idStrategy;
        this.initialSequenceIndex = initialSequenceIndex;
        this.errorHandler = errorHandler;
        this.sessionIdGenerator = sessionIdGenerator;
        loadBuffer();
        allSessions.addAll(compositeToContext.values());
    }

    private void loadBuffer()
    {
        checkByteBuffer();
        initialiseBuffer();

        headerDecoder.wrap(buffer, 0);
        final boolean needsUpgrading = headerDecoder.version() <= VERSION_WITHOUT_FIX_DICTIONARY;
        final FixDictionary dictionary = needsUpgrading ? FixDictionary.of(FixDictionary.findDefault()) : null;
        final boolean requiresCompaction = readFileSessionInfos(dictionary);

        if (needsUpgrading || requiresCompaction)
        {
            resetBuffer();
            compositeToContext.values().forEach(this::allocateNewSlot);
        }
    }

    private boolean readFileSessionInfos(final FixDictionary dictionary)
    {
        boolean requiresCompaction = false;
        int sectorEnd = 0;
        filePosition = HEADER_SIZE;
        final int lastRecordStart = buffer.capacity() - BLOCK_LENGTH;
        while (filePosition < lastRecordStart)
        {
            sectorEnd = validateSectorChecksum(filePosition, sectorEnd);

            long sessionId = wrap(sessionIdDecoder, filePosition);
            // If filePosition is close enough to the end of the sector then sessionId won't be zero'd
            // even if there's a gap before the next sector because the checksum will be written into
            // some of the bytes of the session Id.
            final int startOfNextChecksum = sectorEnd - CHECKSUM_SIZE;
            final int endOfSessionId = filePosition + SessionIdDecoder.sessionIdEncodingLength();
            if (sessionId == 0 || (startOfNextChecksum < endOfSessionId && filePosition != sectorEnd))
            {
                final int nextSectorPeekPosition = sectorEnd;
                if (nextSectorPeekPosition > lastRecordStart)
                {
                    return requiresCompaction;
                }

                sessionId = wrap(sessionIdDecoder, nextSectorPeekPosition);
                if (sessionId == 0)
                {
                    return requiresCompaction;
                }
                else
                {
                    filePosition = nextSectorPeekPosition;
                }
            }
            else if (sessionId == Session.UNKNOWN)
            {
                // relocated session slot, skip
                final int compositeKeyLength = sessionIdDecoder.compositeKeyLength();
                sessionIdDecoder.skipLastFixDictionary();
                filePosition = sessionIdDecoder.limit() + compositeKeyLength;
                requiresCompaction = true;
            }
            else
            {
                final int sequenceIndex = sessionIdDecoder.sequenceIndex();
                final long lastLogonTime = sessionIdDecoder.logonTime();
                final long lastSequenceResetTime = sessionIdDecoder.lastSequenceResetTime();
                final int compositeKeyLength = sessionIdDecoder.compositeKeyLength();
                final String lastFixDictionary = sessionIdDecoder.lastFixDictionary();

                filePosition = sessionIdDecoder.limit();
                final CompositeKey compositeKey = idStrategy.load(
                    buffer, filePosition, compositeKeyLength);
                if (compositeKey == null)
                {
                    return requiresCompaction;
                }

                try
                {
                    final FixDictionary thisDictionary = (dictionary == null) ?
                        FixDictionary.of(FixDictionary.find(lastFixDictionary)) : dictionary;
                    final SessionContext sessionContext = new SessionContext(compositeKey,
                        sessionId, sequenceIndex, lastLogonTime, lastSequenceResetTime, this,
                        sessionIdDecoder.offset(),
                        initialSequenceIndex, thisDictionary, reproductionEnabled);
                    compositeToContext.put(compositeKey, sessionContext);
                }
                catch (final Exception e)
                {
                    // Don't block the startup / load of the engine if there's an invalid fix dictionary error
                    // just report it to the user
                    errorHandler.onError(e);
                }

                sessionIdGenerator.onExistingSessionId(sessionId);

                filePosition += compositeKeyLength;
            }
        }
        return requiresCompaction;
    }

    private long wrap(final SessionIdDecoder sessionIdDecoder, final int nextSectorPeekPosition)
    {
        sessionIdDecoder.wrap(buffer, nextSectorPeekPosition, headerDecoder.blockLength(), headerDecoder.version());
        return sessionIdDecoder.sessionId();
    }

    private void checkByteBuffer()
    {
        if (byteBuffer == null)
        {
            throw new IllegalStateException("Must use atomic buffer backed by a byte buffer");
        }
    }

    private void initialiseBuffer()
    {
        if (LoggerUtil.initialiseBuffer(
            buffer,
            headerEncoder,
            headerDecoder,
            sessionIdEncoder.sbeSchemaId(),
            sessionIdEncoder.sbeTemplateId(),
            actingVersion,
            actingBlockLength,
            errorHandler))
        {
            updateChecksum(0, FIRST_CHECKSUM_LOCATION);
            mappedFile.force();
        }
    }

    private int validateSectorChecksum(final int position, final int sectorEnd)
    {
        if (position > sectorEnd)
        {
            final int nextSectorEnd = sectorEnd + SECTOR_SIZE;
            final int nextChecksum = nextSectorEnd - CHECKSUM_SIZE;
            crc32.reset();
            byteBuffer.clear();
            ByteBufferUtil.position(byteBuffer, sectorEnd);
            ByteBufferUtil.limit(byteBuffer, nextChecksum);
            crc32.update(byteBuffer);
            final int calculatedChecksum = (int)crc32.getValue();
            final int savedChecksum = buffer.getInt(nextChecksum);
            validateCheckSum(
                "session ids", sectorEnd, nextSectorEnd, savedChecksum, calculatedChecksum, errorHandler);
            return nextSectorEnd;
        }

        return sectorEnd;
    }

    public SessionContext onLogon(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        final SessionContext sessionContext = newSessionContext(compositeKey, fixDictionary);

        if (!currentlyAuthenticatedSessionIds.add(sessionContext.sessionId()))
        {
            return DUPLICATE_SESSION;
        }

        return sessionContext;
    }

    SessionContext newSessionContext(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        final SessionContext context = compositeToContext.computeIfAbsent(
            compositeKey,
            key -> onNewLogon(key, fixDictionary));
        if (context.lastFixDictionary() != fixDictionary)
        {
            context.ensureFixDictionary(fixDictionary);
        }
        return context;
    }

    private SessionContext onNewLogon(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        final long sessionId = sessionIdGenerator.nextId();
        final SessionContext sessionContext = assignSessionId(
            compositeKey,
            sessionId,
            SessionInfo.UNKNOWN_SEQUENCE_INDEX,
            fixDictionary);
        allSessions.add(sessionContext);
        return sessionContext;
    }

    private SessionContext assignSessionId(
        final CompositeKey compositeKey,
        final long sessionId,
        final int sequenceIndex,
        final FixDictionary fixDictionary)
    {
        final SessionContext context = new SessionContext(
            compositeKey, sessionId,
            sequenceIndex,
            Session.UNKNOWN_TIME,
            Session.UNKNOWN_TIME,
            this,
            0,
            initialSequenceIndex,
            fixDictionary,
            reproductionEnabled);

        allocateNewSlot(context);

        return context;
    }

    private void allocateNewSlot(final SessionContext context)
    {
        final CompositeKey compositeKey = context.sessionKey();
        final long sessionId = context.sessionId();
        final int sequenceIndex = context.sequenceIndex();
        final FixDictionary fixDictionary = context.lastFixDictionary();

        final String fixDictionaryName = nameOf(fixDictionary);
        int keyPosition = OUT_OF_SPACE;
        final int compositeKeyLength = idStrategy.save(compositeKey, compositeKeyBuffer, 0);
        if (compositeKeyLength == INSUFFICIENT_SPACE)
        {
            errorHandler.onError(new IllegalStateException(String.format(
                "Unable to save record session id %d for %s, because the buffer is too small",
                sessionId,
                compositeKey)));
            context.filePosition(OUT_OF_SPACE);
        }
        else
        {
            if (filePosition != OUT_OF_SPACE)
            {
                final int length = (BLOCK_LENGTH + SessionIdEncoder.lastFixDictionaryHeaderLength() +
                    fixDictionaryName.length() + compositeKeyLength);
                filePosition = sectorFramer.claim(filePosition, length);
                keyPosition = filePosition;
                if (filePosition == OUT_OF_SPACE)
                {
                    errorHandler.onError(new IllegalStateException(
                        "Run out of space when storing: " + compositeKey));
                }
                else
                {
                    sessionIdEncoder
                        .wrap(buffer, filePosition)
                        .sessionId(sessionId)
                        .sequenceIndex(sequenceIndex)
                        .logonTime(context.lastLogonTimeInNs())
                        .lastSequenceResetTime(context.lastSequenceResetTime())
                        .compositeKeyLength(compositeKeyLength)
                        .lastFixDictionary(fixDictionaryName);
                    filePosition = sessionIdEncoder.limit();

                    buffer.putBytes(filePosition, compositeKeyBuffer, 0, compositeKeyLength);
                    filePosition += compositeKeyLength;

                    updateChecksum(sectorFramer.sectorStart(), sectorFramer.checksumOffset());
                    mappedFile.force();
                }
            }
        }

        context.filePosition(keyPosition);
    }

    private String nameOf(final FixDictionary fixDictionary)
    {
        return fixDictionary.getClass().getName();
    }

    public void sequenceReset(final long sessionId, final long resetTimeInNs)
    {
        final Entry<CompositeKey, SessionContext> entry = lookupById(sessionId);
        if (entry != null)
        {
            final SessionContext context = entry.getValue();
            context.onSequenceReset(resetTimeInNs);
        }
    }

    public void onSequenceIndex(final long sessionId, final long resetTimeInNs, final int sequenceIndex)
    {
        final Entry<CompositeKey, SessionContext> entry = lookupById(sessionId);
        if (entry != null)
        {
            final SessionContext context = entry.getValue();
            context.onSequenceIndex(resetTimeInNs, sequenceIndex);
        }
    }

    Entry<CompositeKey, SessionContext> lookupById(final long sessionId)
    {
        for (final Entry<CompositeKey, SessionContext> entry : compositeToContext.entrySet())
        {
            if (entry.getValue().sessionId() == sessionId)
            {
                return entry;
            }
        }

        return null;
    }

    // TODO: optimisation, more efficient checksumming, only checksum new data
    private void updateChecksum(final int start, final int checksumOffset)
    {
        final int endOfData = checksumOffset;
        byteBuffer.clear();
        ByteBufferUtil.position(byteBuffer, start);
        ByteBufferUtil.limit(byteBuffer, endOfData);
        crc32.reset();
        crc32.update(byteBuffer);
        final int checksumValue = (int)crc32.getValue();
        buffer.putInt(checksumOffset, checksumValue);
    }

    public void onDisconnect(final long sessionId)
    {
        currentlyAuthenticatedSessionIds.remove(sessionId);
    }

    public void reset(final File backupLocation)
    {
        if (!currentlyAuthenticatedSessionIds.isEmpty())
        {
            throw new IllegalStateException(
                "There are currently authenticated sessions: " + currentlyAuthenticatedSessionIds);
        }

        sessionIdGenerator.reset();
        compositeToContext.clear();
        allSessions.clear();

        if (backupLocation != null)
        {
            mappedFile.transferTo(backupLocation);
        }

        resetBuffer();
    }

    private void resetBuffer()
    {
        buffer.setMemory(0, buffer.capacity(), (byte)0);
        initialiseBuffer();
        filePosition = HEADER_SIZE;
    }

    void updateSavedData(final SessionContext context, final int filePosition)
    {
        final String fixDictionaryName = nameOf(context.lastFixDictionary());

        sessionIdDecoder.wrap(buffer, filePosition, actingBlockLength, actingVersion);
        sessionIdEncoder.wrap(buffer, filePosition);
        if (sessionIdDecoder.lastFixDictionaryLength() != fixDictionaryName.length())
        {
            // delete old slot
            sessionIdEncoder.sessionId(Session.UNKNOWN);

            allocateNewSlot(context);

            // Recalculate checksum from delete if needed
            if (nextSectorStart(filePosition) != nextSectorStart(this.filePosition))
            {
                updateSectorChecksum(filePosition);
            }
        }
        else
        {
            sessionIdEncoder
                .sequenceIndex(context.sequenceIndex())
                .logonTime(context.lastLogonTimeInNs())
                .lastSequenceResetTime(context.lastSequenceResetTime());

            updateSectorChecksum(filePosition);
        }
    }

    private void updateSectorChecksum(final int filePosition)
    {
        final int start = nextSectorStart(filePosition) - SECTOR_SIZE;
        final int checksumOffset = start + SECTOR_DATA_LENGTH;
        updateChecksum(start, checksumOffset);

        mappedFile.force();
    }

    long lookupSessionId(final CompositeKey compositeKey)
    {
        final SessionContext sessionContext = compositeToContext.get(compositeKey);
        if (sessionContext == null)
        {
            return Session.UNKNOWN;
        }
        return sessionContext.sessionId();
    }

    public boolean isAuthenticated(final long sessionId)
    {
        return currentlyAuthenticatedSessionIds.contains(sessionId);
    }

    public boolean isKnownSessionId(final long sessionId)
    {
        return lookupById(sessionId) != null;
    }

    public List<SessionInfo> allSessions()
    {
        return allSessions;
    }

    int filePosition()
    {
        return filePosition;
    }
}
