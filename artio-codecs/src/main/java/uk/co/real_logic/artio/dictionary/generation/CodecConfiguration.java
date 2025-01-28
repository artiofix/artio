/*
 * Copyright 2020-2021 Adaptive Financial Consulting Ltd.
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

import java.io.InputStream;
import java.util.function.BiFunction;


public final class CodecConfiguration
{
    /**
     * Boolean system property to turn on or off duplicated fields validation. Defaults to false.
     * <p>
     * Fix specification vol 1:
     * A tag number (field) should only appear in a message once. If it appears more than once in the message it should
     * be considered an error with the specification document.
     * <p>
     * Turning this option on may break parsing: this option should be used for support fix specification with error
     * only. It is recommended, where possible, to correct your FIX XML file instead of using this option in order
     * to support an invalid XML file.
     * <br>
     * The duplicated fields is allowed in the following case:
     * <pre>
     * message body:
     * field;
     * repeating group:
     * the_other_field+
     * field;
     * </pre>
     */
    public static final String FIX_CODECS_ALLOW_DUPLICATE_FIELDS_PROPERTY = "fix.codecs.allow_duplicate_fields";

    /**
     * Boolean system property to avoid throwing when an optional string field is unset.
     * <p>
     * This is useful to call getters accepting an {@link org.agrona.AsciiSequenceView}
     * without checking whether the field is set.
     */
    public static final String WRAP_EMPTY_BUFFER = "fix.codecs.wrap_empty_buffer";
    public static final String PARENT_PACKAGE_PROPERTY = "fix.codecs.parent_package";
    public static final String FLYWEIGHTS_ENABLED_PROPERTY = "fix.codecs.flyweight";
    public static final String REJECT_UNKNOWN_ENUM_VALUE_PROPERTY = "reject.unknown.enum.value";
    public static final String FLOAT_OVERFLOW_HANDLER_PROPERTY = "float.overflow.handler";
    public static final String FIX_TAGS_IN_JAVADOC = "fix.codecs.tags_in_javadoc";

    public static final String DEFAULT_PARENT_PACKAGE = "uk.co.real_logic.artio";
    public static final String DEFAULT_FIX_TAGS_IN_JAVADOC = "true";

    private String parentPackage = System.getProperty(PARENT_PACKAGE_PROPERTY, DEFAULT_PARENT_PACKAGE);
    private boolean flyweightsEnabled = Boolean.getBoolean(FLYWEIGHTS_ENABLED_PROPERTY);
    private boolean wrapEmptyBuffer = Boolean.getBoolean(WRAP_EMPTY_BUFFER);
    private boolean fixTagsInJavadoc = Boolean.parseBoolean(System.getProperty(
        FIX_TAGS_IN_JAVADOC, DEFAULT_FIX_TAGS_IN_JAVADOC));
    private SharedCodecConfiguration sharedCodecConfiguration;

    private String codecRejectUnknownEnumValueEnabled;
    private String outputPath;

    private BiFunction<String, String, OutputManager> outputManagerFactory = PackageOutputManager::new;
    private final GeneratorDictionaryConfiguration nonSharedDictionary =
        new GeneratorDictionaryConfiguration(null, null, null,
        Boolean.getBoolean(FIX_CODECS_ALLOW_DUPLICATE_FIELDS_PROPERTY));
    private String decimalFloatOverflowHandler = null;

    public CodecConfiguration()
    {
    }

    /**
     * Sets the output path where codecs are generated. This should be a valid path on your local filesystem where the
     * codec generator has the ability to write files. Required.
     *
     * If this doesn't already exist it will be created.
     *
     * @param outputPath the output path where codecs are generated
     * @return this
     */
    public CodecConfiguration outputPath(final String outputPath)
    {
        this.outputPath = outputPath;
        return this;
    }

    /**
     * Sets the full qualified class name of a <code>{@link uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler}</code> implementation.
     * If not set, the default behaviour is executed when there's an overflow.
     * @param decimalFloatOverflowHandler full qualified class name of a DecimalFloat overflow handler
     * @return this
     */
    public CodecConfiguration decimalFloatOverflowHandler(
        final String decimalFloatOverflowHandler)
    {
        this.decimalFloatOverflowHandler = decimalFloatOverflowHandler;
        return this;
    }

    public String getDecimalFloatOverflowHandler()
    {
        return decimalFloatOverflowHandler;
    }

    /**
     * Sets the parent package where classes are generated. Optional, defaults to {@link #DEFAULT_PARENT_PACKAGE}.
     * Different parent packages can be used to use multiple different fix dictionary versions, see the
     * <a href="https://github.com/real-logic/artio/wiki/Multiple-FIX-Versions">wiki</a> for details.
     *
     * @param parentPackage the parent package where classes are generated.
     * @return this
     */
    public CodecConfiguration parentPackage(final String parentPackage)
    {
        this.parentPackage = parentPackage;
        return this;
    }

    public CodecConfiguration flyweightsEnabled(final boolean flyweightsEnabled)
    {
        this.flyweightsEnabled = flyweightsEnabled;
        return this;
    }

    /**
     * Suppresses checks for the presence of optional string fields (i.e. no exception is
     * thrown when unset, instead the AsciiSequenceView wraps an empty buffer).
     *
     * Defaults to the value of {@link #WRAP_EMPTY_BUFFER} system property.
     *
     * @param wrapEmptyBuffer true to suppress check of optional strings, false to keep checking (default)
     * @return this
     */
    public CodecConfiguration wrapEmptyBuffer(final boolean wrapEmptyBuffer)
    {
        this.wrapEmptyBuffer = wrapEmptyBuffer;
        return this;
    }

    /**
     * Allow duplicate fields. Executable documentation can be found in the test "DuplicateFieldsTest".
     *
     * Defaults to the value of {@link #FIX_CODECS_ALLOW_DUPLICATE_FIELDS_PROPERTY} if set.
     *
     * Can be overridden for individual dictionaries in shared codec mode using
     * {@link SharedCodecConfiguration#withDictionary(String, boolean, String...)} and
     * {@link SharedCodecConfiguration#withDictionary(String, boolean, InputStream...)}. If not overridden
     * then shared codecs default to this value.
     *
     * @param allowDuplicateFields true to enable, false to disable
     * @return this
     */
    public CodecConfiguration allowDuplicateFields(final boolean allowDuplicateFields)
    {
        nonSharedDictionary.allowDuplicateFields(allowDuplicateFields);
        return this;
    }

    /**
     * Provide the XML file, or files, that are used to generate the Dictionaries. Multiple dictionary files can be
     * used to provide split data and transport XML files as used by FIX 5.0 / FIXT. If you want to generate a shared
     * dictionary then please use {@link SharedCodecConfiguration#withDictionary(String, boolean, String...)} method and not
     * this one. {@link #fileStreams(InputStream...)} is an alternative configuration option that lets you provide
     * inputstreams as the source of your XML files.
     *
     * @param fileNames the file names to use as sources of XML documents
     * @return this
     */
    public CodecConfiguration fileNames(final String... fileNames)
    {
        nonSharedDictionary.fileNames(fileNames);
        return this;
    }

    /**
     * Provide the XML document, or documents, that are used to generate the Dictionaries as instance of
     * {@link InputStream}. Multiple dictionary files can be
     * used to provide split data and transport XML files as used by FIX 5.0 / FIXT. If you want to generate a shared
     * dictionary then please use {@link SharedCodecConfiguration#withDictionary(String, boolean, InputStream...)} method and not
     * this one. {@link #fileNames(String...)} is an alternative configuration option that lets you provide
     * file names as the source of your XML files.
     *
     * @param fileStreams the file streams to use as sources of XML documents
     * @return this
     */
    public CodecConfiguration fileStreams(final InputStream... fileStreams)
    {
        nonSharedDictionary.fileStreams(fileStreams);
        return this;
    }

    CodecConfiguration outputManagerFactory(
        final BiFunction<String, String, OutputManager> outputManagerFactory)
    {
        this.outputManagerFactory = outputManagerFactory;
        return this;
    }

    /**
     * String representing a Java expressions that evaluates to a boolean within the codec that states whether
     * an unknown enum value within a codec should be rejected or not. Evaluation to true rejects. This could be
     * a constant value or a reference to some other Java code that evaluates to a boolean.
     *
     * For example <code>"true"</code> or <code>"ExternalConfigClass.REJECT_UNKNOWN_ENUM"</code>.
     *
     * @param codecRejectUnknownEnumValueEnabled the String
     * @return this
     */
    public CodecConfiguration codecRejectUnknownEnumValueEnabled(final String codecRejectUnknownEnumValueEnabled)
    {
        this.codecRejectUnknownEnumValueEnabled = codecRejectUnknownEnumValueEnabled;
        return this;
    }

    /**
     * Enable the generation of shared codecs. This returns an object upon which configuration options can be set.
     *
     * @return the shared codec configuration object
     */
    public SharedCodecConfiguration sharedCodecsEnabled()
    {
        sharedCodecConfiguration = new SharedCodecConfiguration(nonSharedDictionary().allowDuplicateFields());
        return sharedCodecConfiguration;
    }

    /**
     * Sets whether to generate javadoc tags with the FIX tags in. Defaults to true. Can be configured via properties
     * using <code>-Dfix.codecs.tags_in_javadoc=false</code>.
     *
     * @param fixTagsInJavadoc true to generate javadoc tags with the FIX tags in.
     * @return this
     */
    public CodecConfiguration fixTagsInJavadoc(final boolean fixTagsInJavadoc)
    {
        this.fixTagsInJavadoc = fixTagsInJavadoc;
        return this;
    }

    String outputPath()
    {
        return outputPath;
    }

    String parentPackage()
    {
        return parentPackage;
    }

    boolean flyweightsEnabled()
    {
        return flyweightsEnabled;
    }

    boolean wrapEmptyBuffer()
    {
        return wrapEmptyBuffer;
    }

    String codecRejectUnknownEnumValueEnabled()
    {
        return codecRejectUnknownEnumValueEnabled;
    }

    SharedCodecConfiguration sharedCodecConfiguration()
    {
        return sharedCodecConfiguration;
    }

    public GeneratorDictionaryConfiguration nonSharedDictionary()
    {
        return nonSharedDictionary;
    }

    BiFunction<String, String, OutputManager> outputManagerFactory()
    {
        return outputManagerFactory;
    }

    public boolean fixTagsInJavadoc()
    {
        return fixTagsInJavadoc;
    }

    void conclude()
    {
        if (outputPath() == null)
        {
            throw new IllegalArgumentException("Missing outputPath() configuration property");
        }

        if (codecRejectUnknownEnumValueEnabled == null)
        {
            final String rejectUnknownEnumPropertyValue = System.getProperty(REJECT_UNKNOWN_ENUM_VALUE_PROPERTY);
            codecRejectUnknownEnumValueEnabled = rejectUnknownEnumPropertyValue != null ?
                rejectUnknownEnumPropertyValue : Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY;
        }

        if (sharedCodecConfiguration != null)
        {
            if (nonSharedDictionary.hasStreams())
            {
                throw new IllegalArgumentException(
                    "Cannot mix shared codec configuration with providing file streams or names via the non-shared " +
                        "configuration option. If you want to provide dictionaries for sharing then use " +
                        "SharedCodecConfiguration.withDictionary().");
            }
        }
        else
        {
            if (!nonSharedDictionary.hasStreams())
            {
                throw new IllegalArgumentException(
                    "Please provide a path to the XML files either through the fileNames() or fileStreams() option.");
            }
        }
        if (decimalFloatOverflowHandler == null)
        {

            decimalFloatOverflowHandler = System.getProperty(FLOAT_OVERFLOW_HANDLER_PROPERTY);
        }
    }
}
