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
package uk.co.real_logic.artio.dictionary.generation;

import org.agrona.generation.OutputManager;
import org.agrona.generation.PackageOutputManager;
import uk.co.real_logic.artio.builder.RejectUnknownEnumValue;
import uk.co.real_logic.artio.builder.RejectUnknownField;
import uk.co.real_logic.artio.builder.Validation;
import uk.co.real_logic.artio.dictionary.DictionaryParser;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public final class CodecGenerator
{
    public static final String SHARED_DIR_NAME = "shared";

    public static void generate(final CodecConfiguration configuration) throws Exception
    {
        configuration.conclude();

        final String outputPath = configuration.outputPath();
        final String codecRejectUnknownEnumValueEnabled = configuration.codecRejectUnknownEnumValueEnabled();

        final boolean hasSharedCodecs = configuration.sharedCodecConfiguration() != null;
        if (hasSharedCodecs)
        {
            generateSharedDictionaries(
                configuration, outputPath, codecRejectUnknownEnumValueEnabled);
        }
        else
        {
            generateNormalDictionaries(
                configuration, outputPath, codecRejectUnknownEnumValueEnabled);
        }
    }

    private static void generateSharedDictionaries(
        final CodecConfiguration configuration,
        final String outputPath,
        final String codecRejectUnknownEnumValueEnabled)
    {
        final SharedCodecConfiguration sharedCodecs = configuration.sharedCodecConfiguration();
        final List<Dictionary> inputDictionaries = new ArrayList<>();
        for (final GeneratorDictionaryConfiguration dictionaryConfig : sharedCodecs.dictionaries())
        {
            final String name = normalise(dictionaryConfig.dictionaryName());
            try
            {
                final DictionaryParser parser = new DictionaryParser(dictionaryConfig.allowDuplicateFields());
                final Dictionary dictionary = parseStreams(parser, dictionaryConfig.toStreams());
                dictionary.name(name);
                inputDictionaries.add(dictionary);
            }
            catch (final Exception e)
            {
                throw new IllegalArgumentException("Unable to parse: " + name, e);
            }
        }

        new CodecSharer(inputDictionaries).share();

        final boolean splitDirectories = sharedCodecs.splitDirectories();
        inputDictionaries.forEach(dictionary ->
        {
            final String suffixDir = dictionary.shared() ? SHARED_DIR_NAME : dictionary.name();
            final String dictOutputPath = outputPath + (splitDirectories ? File.separatorChar + suffixDir : "");
            generateDictionary(configuration, dictOutputPath, codecRejectUnknownEnumValueEnabled, dictionary);
        });
    }

    private static String normalise(final String dictionaryName)
    {
        return dictionaryName.replace('.', '_');
    }

    private static void generateNormalDictionaries(
        final CodecConfiguration configuration,
        final String outputPath,
        final String codecRejectUnknownEnumValueEnabled) throws Exception
    {
        final GeneratorDictionaryConfiguration nonSharedDictionary = configuration.nonSharedDictionary();
        final DictionaryParser parser = new DictionaryParser(nonSharedDictionary.allowDuplicateFields());
        final InputStream[] fileStreams = nonSharedDictionary.toStreams();
        try
        {
            final Dictionary dictionary = parseStreams(parser, fileStreams);
            generateDictionary(configuration, outputPath, codecRejectUnknownEnumValueEnabled, dictionary);
        }
        finally
        {
            Exceptions.closeAll(fileStreams);
        }
    }

    private static Dictionary parseStreams(final DictionaryParser parser, final InputStream[] fileStreams)
        throws Exception
    {
        Dictionary dictionary = null;

        for (final InputStream fileStream : fileStreams)
        {
            dictionary = parser.parse(fileStream, dictionary);
        }

        return dictionary;
    }

    private static void generateDictionary(
        final CodecConfiguration configuration,
        final String outputPath,
        final String codecRejectUnknownEnumValueEnabled,
        final Dictionary dictionary)
    {
        String parentPackage = configuration.parentPackage();
        final String name = dictionary.name();
        if (name != null)
        {
            parentPackage += "." + name;
        }

        final String encoderPackage = parentPackage + ".builder";
        final String decoderPackage = parentPackage + ".decoder";
        final String decoderFlyweightPackage = parentPackage + ".decoder_flyweight";

        final BiFunction<String, String, OutputManager> outputManagerFactory =
            configuration.outputManagerFactory();
        final OutputManager parentOutput = outputManagerFactory.apply(outputPath, parentPackage);
        final OutputManager decoderOutput = outputManagerFactory.apply(outputPath, decoderPackage);
        final OutputManager encoderOutput = outputManagerFactory.apply(outputPath, encoderPackage);

        new EnumGenerator(dictionary, parentPackage, parentOutput).generate();
        new ConstantGenerator(dictionary, parentPackage, configuration.parentPackage(), parentOutput).generate();

        new FixDictionaryGenerator(
            dictionary,
            parentOutput,
            encoderPackage,
            decoderPackage,
            parentPackage).generate();

        new EncoderGenerator(
            dictionary,
            encoderPackage,
            parentPackage,
            encoderOutput,
            Validation.class,
            RejectUnknownField.class,
            RejectUnknownEnumValue.class,
            codecRejectUnknownEnumValueEnabled,
            configuration.fixTagsInJavadoc()).generate();

        new DecoderGenerator(
            dictionary,
            1,
            decoderPackage,
            parentPackage,
            encoderPackage,
            decoderOutput,
            Validation.class,
            RejectUnknownField.class,
            RejectUnknownEnumValue.class,
            false,
            configuration.wrapEmptyBuffer(),
            codecRejectUnknownEnumValueEnabled,
            configuration.fixTagsInJavadoc(),
            configuration.getDecimalFloatOverflowHandler()).generate();

        new PrinterGenerator(dictionary, decoderPackage, decoderOutput).generate();
        new AcceptorGenerator(dictionary, decoderPackage, decoderOutput).generate();

        if (configuration.flyweightsEnabled())
        {
            final PackageOutputManager flyweightDecoderOutput =
                new PackageOutputManager(outputPath, decoderFlyweightPackage);

            new DecoderGenerator(
                dictionary,
                1,
                decoderFlyweightPackage,
                parentPackage,
                encoderPackage, flyweightDecoderOutput,
                Validation.class,
                RejectUnknownField.class,
                RejectUnknownEnumValue.class,
                true,
                configuration.wrapEmptyBuffer(),
                codecRejectUnknownEnumValueEnabled,
                configuration.fixTagsInJavadoc(),
                configuration.getDecimalFloatOverflowHandler()).generate();
        }
    }
}
