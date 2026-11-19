/*
 * Copyright 2020 Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.dictionary;

import org.agrona.SystemUtil;
import org.agrona.generation.CompilerUtil;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Validation;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;

public final class CodecValidationInspector
{
    private static final String TEMP_DIR_NAME = SystemUtil.tmpDirName();

    public static void main(final String[] args) throws Exception
    {
        final String dictionaryFile = args[0];
        String message = args[1];
        final String codecName = args[2];

        final byte[] messageBytes;

        final File messageFile = new File(message);
        if (messageFile.exists())
        {
            System.out.println("Using message parameter as a path");
            messageBytes = Files.readAllBytes(messageFile.toPath());
            System.out.println("message = " + new String(messageBytes, US_ASCII));
        }
        else
        {
            if (args.length > 3)
            {
                final char replacementChar = args[3].charAt(0);
                message = message.replace(replacementChar, '\001');
            }

            System.out.println("message = " + message);

            messageBytes = message.getBytes(US_ASCII);
        }

        final String outputDirPath = "/tmp/fix-codecs";

        CodecGenerationTool.main(new String[]{ outputDirPath, dictionaryFile });

        final File outputDir = new File(outputDirPath);
        final List<File> files = new ArrayList<>();
        find(outputDir, files);

        System.out.println("Compiling " + files);

        compileOnDisk(files);

        System.out.println("CODEC_VALIDATION_ENABLED = " + Validation.CODEC_VALIDATION_ENABLED);
        final URLClassLoader classLoader = new URLClassLoader(new URL[]{outputDir.toURI().toURL()});
        final Class<?> decoderClass = classLoader.loadClass(codecName);
        final Decoder decoder = (Decoder)decoderClass.getConstructor().newInstance();
        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(messageBytes);
        final int length = buffer.capacity();
        final int decodedLength;
        try
        {
            decodedLength = decoder.decode(buffer, 0, length);
        }
        catch (final Exception e)
        {
            System.out.println(decoder);
            throw e;
        }

        if (decodedLength != length)
        {
            System.out.println("decodedLength = " + decodedLength + " vs length = " + length);
        }

        if (decoder.validate())
        {
            System.out.println("Codec Validated Ok!");
        }
        else
        {
            System.out.println("Failed Validation");
            System.out.println("Reason = " + RejectReason.decode(decoder.rejectReason()));
            System.out.println("invalidTagId = " + decoder.invalidTagId());
        }

        System.out.println(decoder);
    }

    private static void find(final File dir, final List<File> files)
    {
        for (final File file : dir.listFiles())
        {
            if (file.isFile() && file.canRead() && file.getName().endsWith(".java"))
            {
                files.add(file);
            }

            if (file.isDirectory())
            {
                find(file, files);
            }
        }
    }

    public static void compileOnDisk(final Collection<File> files)
        throws IOException
    {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (null == compiler)
        {
            throw new IllegalStateException("JDK required to run tests. JRE is not sufficient.");
        }

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null))
        {
            final ArrayList<String> options = new ArrayList<>(Arrays.asList(
                "-classpath", System.getProperty("java.class.path") + File.pathSeparator + TEMP_DIR_NAME));

            final Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(files);
            final JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits);

            if (!CompilerUtil.compile(diagnostics, task))
            {
                System.err.println("Compilation Failed!");
            }
        }
    }
}
