/*
 * Copyright 2026 Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.dictionary.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecGeneratorStreamClosingTest
{
    private static final String MALFORMED_XML = "<malformed major=";

    @TempDir
    File tempDir;

    // Every file the generator opens, so the tests can check each one was closed
    private final List<TrackingInputStream> openedStreams = new ArrayList<>();

    private String outputPath;
    private String existingFile;
    private String malformedFile;
    private String missingFile;

    @BeforeEach
    void setUp() throws Exception
    {
        outputPath = new File(tempDir, "out").getPath();

        final File existing = new File(tempDir, "existing.xml");
        // Never parsed in any test, so its content doesn't matter
        Files.write(existing.toPath(), "<random/>".getBytes(StandardCharsets.US_ASCII));
        existingFile = existing.getPath();

        final File malformed = new File(tempDir, "malformed.xml");
        Files.write(malformed.toPath(), MALFORMED_XML.getBytes(StandardCharsets.US_ASCII));
        malformedFile = malformed.getPath();

        missingFile = new File(tempDir, "missing.xml").getPath();
        // No Files.write() because we want this to be intentionally missing
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterDictionaryFileIsMissing()
    {
        final CodecConfiguration config = newConfig().fileNames(existingFile, missingFile);

        assertThrows(IllegalArgumentException.class, () -> CodecGenerator.generate(config));

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterSharedDictionaryFileIsMissing()
    {
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary("dict", existingFile, missingFile);

        assertThrows(IllegalArgumentException.class, () -> CodecGenerator.generate(config));

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedFilesWhenASharedDictionaryFileFailsToParse()
    {
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary("dict", malformedFile, existingFile);

        assertThrows(IllegalArgumentException.class, () -> CodecGenerator.generate(config));

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedFilesWhenADictionaryFileFailsToParse()
    {
        final CodecConfiguration config = newConfig().fileNames(malformedFile, existingFile);

        assertThrows(Exception.class, () -> CodecGenerator.generate(config));

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedStreamsWhenASharedDictionaryStreamFailsToParse()
    {
        final TrackingInputStream unparsed = new TrackingInputStream(new ByteArrayInputStream(new byte[0]));
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary("dict", malformedStream(), unparsed);

        assertThrows(IllegalArgumentException.class, () -> CodecGenerator.generate(config));

        assertTrue(unparsed.closed, "unparsed stream should be closed");
    }

    @Test
    void shouldOpenEachFileOnceAndReturnTheSameStreamsOnLaterCalls() throws Exception
    {
        final File secondFile = new File(tempDir, "second.xml");
        Files.write(secondFile.toPath(), "<fix/>".getBytes(StandardCharsets.US_ASCII));
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, secondFile.getPath() }, false);

        final InputStream[] streams = dictionary.toStreams(this::openTracked);

        // One stream per file, in the same order as the file names.
        assertEquals(2, streams.length);
        assertSame(openedStreams.get(0), streams[0]);
        assertSame(openedStreams.get(1), streams[1]);

        // The streams are kept in the fileStreams field, so a later call returns them without opening the files again.
        assertSame(streams, dictionary.toStreams(this::openTracked));
        assertEquals(2, openedStreams.size());

        // Closing them is the caller's job (CodecGenerator), not toStreams'.
        assertFalse(openedStreams.get(0).closed);
        assertFalse(openedStreams.get(1).closed);
        Exceptions.closeAll(streams);
    }

    @Test
    void shouldNotKeepPartiallyOpenedStreamsWhenAFileIsMissing()
    {
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, missingFile }, false);

        final String missingFileMessage = "xmlFile does not exist: " + new File(missingFile).getAbsolutePath();

        final IllegalArgumentException firstCall = assertThrows(
            IllegalArgumentException.class, () -> dictionary.toStreams(this::openTracked));
        assertEquals(missingFileMessage, firstCall.getMessage());

        final IllegalArgumentException retry = assertThrows(
            IllegalArgumentException.class, () -> dictionary.toStreams(this::openTracked),
            "a retry should open the files again rather than return the partially filled array");
        assertEquals(missingFileMessage, retry.getMessage());
    }

    private CodecConfiguration newConfig()
    {
        return new CodecConfiguration()
            .outputPath(outputPath)
            .fileOpener(this::openTracked);
    }

    private InputStream openTracked(final File file) throws FileNotFoundException
    {
        final TrackingInputStream stream = new TrackingInputStream(new FileInputStream(file));
        openedStreams.add(stream);
        return stream;
    }

    private void assertAllOpenedStreamsClosed()
    {
        assertFalse(openedStreams.isEmpty(), "expected the generator to open at least one file");
        for (final TrackingInputStream stream : openedStreams)
        {
            assertTrue(stream.closed, "stream left open after a failed generation");
        }
    }

    private static InputStream malformedStream()
    {
        return new ByteArrayInputStream(MALFORMED_XML.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class TrackingInputStream extends FilterInputStream
    {
        boolean closed;

        TrackingInputStream(final InputStream in)
        {
            super(in);
        }

        @Override
        public void close() throws IOException
        {
            closed = true;
            super.close();
        }
    }
}
