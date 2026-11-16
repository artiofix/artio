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

import org.agrona.generation.StringWriterOutputManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXParseException;
import uk.co.real_logic.artio.dictionary.ExampleDictionary;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecGeneratorStreamClosingTest
{
    private static final String MALFORMED_XML = "<malformed major=";
    private static final String MISSING_FILE_NAME = "missing.xml";
    private static final String SHARED_DICTIONARY_NAME = "test_dict";

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
        Files.writeString(existing.toPath(), "<random/>", StandardCharsets.US_ASCII);
        existingFile = existing.getPath();

        final File malformed = new File(tempDir, "malformed.xml");
        Files.writeString(malformed.toPath(), MALFORMED_XML, StandardCharsets.US_ASCII);
        malformedFile = malformed.getPath();

        missingFile = new File(tempDir, MISSING_FILE_NAME).getPath();
        // No Files.writeString() because we want this to be intentionally missing
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterDictionaryFileIsMissing()
    {
        final CodecConfiguration config = newConfig().fileNames(existingFile, missingFile);

        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(thrown, MISSING_FILE_NAME);

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterSharedDictionaryFileIsMissing()
    {
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary(SHARED_DICTIONARY_NAME, existingFile, missingFile);

        // Shared generation wraps the error with the dictionary name.
        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(thrown, SHARED_DICTIONARY_NAME);
        assertNotNull(thrown.getCause(), "expected the error to be wrapped");
        assertMentions(thrown.getCause(), MISSING_FILE_NAME);

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedFilesWhenASharedDictionaryFileFailsToParse()
    {
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary(SHARED_DICTIONARY_NAME, malformedFile, existingFile);

        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(thrown, SHARED_DICTIONARY_NAME);
        assertInstanceOf(SAXParseException.class, thrown.getCause());

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedFilesWhenADictionaryFileFailsToParse()
    {
        final CodecConfiguration config = newConfig().fileNames(malformedFile, existingFile);

        // Unlike shared generation, the parse error isn't wrapped.
        assertThrows(SAXParseException.class, () -> CodecGenerator.generate(config));

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseUnparsedStreamsWhenASharedDictionaryStreamFailsToParse()
    {
        final TrackingInputStream unparsed = new TrackingInputStream(new ByteArrayInputStream(new byte[0]));
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled().withDictionary(SHARED_DICTIONARY_NAME, malformedStream(), unparsed);

        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(thrown, SHARED_DICTIONARY_NAME);
        assertInstanceOf(SAXParseException.class, thrown.getCause());

        assertTrue(unparsed.closed, "unparsed stream should be closed");
    }

    @Test
    void shouldCloseAllFilesAfterGeneratingADictionary() throws Exception
    {
        final CodecConfiguration config = newConfig().fileNames(dictionaryFile("example_dictionary.xml"));

        CodecGenerator.generate(config);

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseAllFilesAfterGeneratingSharedDictionaries() throws Exception
    {
        // The same dictionaries the shared codec tests use, since not every dictionary can be shared.
        final CodecConfiguration config = newConfig();
        config.sharedCodecsEnabled()
            .withDictionary("dict1", dictionaryFile("shared.dictionary.1.xml"))
            .withDictionary("dict2", dictionaryFile("shared_dictionary_2.xml"));

        CodecGenerator.generate(config);

        assertEquals(2, openedStreams.size());
        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldOpenOneStreamPerFileInOrder() throws Exception
    {
        final File secondFile = new File(tempDir, "second.xml");
        Files.writeString(secondFile.toPath(), "<fix/>", StandardCharsets.US_ASCII);
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, secondFile.getPath() }, false);

        final InputStream[] streams = dictionary.toStreams(this::openTracked);

        // One stream per file, in the same order as the file names.
        assertEquals(2, streams.length);
        assertSame(openedStreams.get(0), streams[0]);
        assertSame(openedStreams.get(1), streams[1]);

        // Closing them is the caller's job (CodecGenerator), not toStreams'.
        assertFalse(openedStreams.get(0).closed);
        assertFalse(openedStreams.get(1).closed);
        Exceptions.closeAll(streams);
    }

    @Test
    void shouldReportTheMissingFileAgainWhenGenerationIsRetried()
    {
        final CodecConfiguration config = newConfig().fileNames(existingFile, missingFile);

        final IllegalArgumentException firstAttempt = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(firstAttempt, MISSING_FILE_NAME);

        // Retrying with the same configuration should check the files again, not reuse a partly opened set.
        final IllegalArgumentException retry = assertThrows(
            IllegalArgumentException.class, () -> CodecGenerator.generate(config));
        assertMentions(retry, MISSING_FILE_NAME);
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterPathIsADirectory()
    {
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, tempDir.getPath() }, false);

        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> dictionary.toStreams(this::openTracked));
        assertMentions(thrown, tempDir.getPath());

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldCloseOpenedFilesWhenALaterFileCannotBeOpened() throws Exception
    {
        // Exists and is a file, so it passes the checks, but opening it fails, e.g. because of its permissions
        final File unreadable = new File(tempDir, "unreadable.xml");
        Files.writeString(unreadable.toPath(), "<random/>", StandardCharsets.US_ASCII);
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, unreadable.getPath() }, false);

        final FileNotFoundException cannotOpen = new FileNotFoundException("Permission denied");
        final GeneratorDictionaryConfiguration.FileOpener opener = (file) ->
        {
            if (file.equals(unreadable))
            {
                throw cannotOpen;
            }
            return openTracked(file);
        };

        final FileNotFoundException thrown = assertThrows(
            FileNotFoundException.class, () -> dictionary.toStreams(opener));
        assertSame(cannotOpen, thrown);

        assertAllOpenedStreamsClosed();
    }

    @Test
    void shouldKeepTheOriginalErrorWhenClosingAnOpenedFileFails()
    {
        final GeneratorDictionaryConfiguration dictionary = new GeneratorDictionaryConfiguration(
            "dict", null, new String[]{ existingFile, missingFile }, false);

        final IOException closeFailure = new IOException("close failed");
        final GeneratorDictionaryConfiguration.FileOpener opener = (file) ->
        {
            final TrackingInputStream stream = openTracked(file);
            stream.closeFailure = closeFailure;
            return stream;
        };

        final IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, () -> dictionary.toStreams(opener));

        // The missing file is still the reported error, and the close failure is attached to it.
        assertMentions(thrown, MISSING_FILE_NAME);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);

        assertAllOpenedStreamsClosed();
    }

    private CodecConfiguration newConfig()
    {
        return new CodecConfiguration()
            .outputPath(outputPath)
            // Keeps generated code in memory rather than writing it to outputPath.
            .outputManagerFactory((ignoredPath, parentPackage) ->
            {
                final StringWriterOutputManager outputManager = new StringWriterOutputManager();
                outputManager.setPackageName(parentPackage);
                return outputManager;
            })
            .fileOpener(this::openTracked);
    }

    // Copies a test dictionary out of the resources, so it can be passed to the generator as a file name.
    private String dictionaryFile(final String resourceName) throws IOException
    {
        final File file = new File(tempDir, resourceName);
        try (InputStream in = ExampleDictionary.class.getResourceAsStream(resourceName))
        {
            assertNotNull(in, "missing test resource: " + resourceName);
            Files.copy(in, file.toPath());
        }
        return file.getPath();
    }

    // Checks the error points at the right file or dictionary, without depending on the exact wording.
    private static void assertMentions(final Throwable error, final String expected)
    {
        final String message = String.valueOf(error.getMessage());
        assertTrue(message.contains(expected), () -> "expected the error to mention " + expected + ": " + message);
    }

    private TrackingInputStream openTracked(final File file) throws FileNotFoundException
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
            assertTrue(stream.closed, "stream left open after generation");
        }
    }

    private static InputStream malformedStream()
    {
        return new ByteArrayInputStream(MALFORMED_XML.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class TrackingInputStream extends FilterInputStream
    {
        boolean closed;
        IOException closeFailure;

        TrackingInputStream(final InputStream in)
        {
            super(in);
        }

        @Override
        public void close() throws IOException
        {
            closed = true;
            super.close();
            if (closeFailure != null)
            {
                throw closeFailure;
            }
        }
    }
}
