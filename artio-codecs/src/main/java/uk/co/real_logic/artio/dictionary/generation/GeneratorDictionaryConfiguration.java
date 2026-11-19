/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

class GeneratorDictionaryConfiguration
{
    private boolean allowDuplicateFields;
    private String dictionaryName;
    private InputStream[] fileStreams;
    private String[] fileNames;

    GeneratorDictionaryConfiguration(
        final String dictionaryName,
        final InputStream[] fileStreams,
        final String[] fileNames,
        final boolean allowDuplicateFields)
    {
        this.dictionaryName = dictionaryName;
        this.fileStreams = fileStreams;
        this.fileNames = fileNames;
        this.allowDuplicateFields = allowDuplicateFields;
    }

    void dictionaryName(final String dictionaryName)
    {
        this.dictionaryName = dictionaryName;
    }

    void fileNames(final String[] fileNames)
    {
        this.fileNames = fileNames;
    }

    void fileStreams(final InputStream[] fileStreams)
    {
        this.fileStreams = fileStreams;
    }

    String dictionaryName()
    {
        return dictionaryName;
    }

    boolean hasStreams()
    {
        return fileStreams != null || fileNames != null;
    }

    boolean allowDuplicateFields()
    {
        return allowDuplicateFields;
    }

    void allowDuplicateFields(final boolean allowDuplicateFields)
    {
        this.allowDuplicateFields = allowDuplicateFields;
    }

    InputStream[] toStreams() throws FileNotFoundException
    {
        // Create input streams from names if not provided.
        if (fileStreams == null)
        {
            if (fileNames == null)
            {
                throw new IllegalArgumentException(
                    "You must provide either the fileNames or fileStream configuration options");
            }

            final int n = fileNames.length;
            fileStreams = new InputStream[n];
            for (int i = 0; i < n; i++)
            {
                final File xmlFile = new File(fileNames[i]);
                if (!xmlFile.exists())
                {
                    throw new IllegalArgumentException("xmlFile does not exist: " + xmlFile.getAbsolutePath());
                }

                if (!xmlFile.isFile())
                {
                    throw new IllegalArgumentException(String.format(
                        "xmlFile [%s] isn't a file, are the arguments the correct way around?",
                        xmlFile));
                }

                // Closed by CodecGenerator
                // codeql[java/input-resource-leak]
                fileStreams[i] = new FileInputStream(xmlFile);
            }
        }

        return fileStreams;
    }
}
