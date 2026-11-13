/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import static uk.co.real_logic.artio.CommonConfiguration.DEBUG_FILE;
import static uk.co.real_logic.artio.CommonConfiguration.DEBUG_FILE_PROPERTY;

public class PrintingDebugAppender extends AbstractDebugAppender
{
    private static final int DEFAULT_BUFFER_SIZE = 10;

    private final PrintWriter output;

    public PrintingDebugAppender()
    {
        output = makeOutputStream();
    }

    private PrintWriter makeOutputStream()
    {
        if (DEBUG_FILE == null)
        {
            return new PrintWriter(System.out);
        }
        else
        {
            try
            {
                return new PrintWriter(new FileOutputStream(DEBUG_FILE));
            }
            catch (final IOException ex)
            {
                throw new IllegalStateException(
                    "Unable to configure DebugLogger, please check " + DEBUG_FILE_PROPERTY, ex);
            }
        }
    }

    class PrintingThreadLocalAppender extends ThreadLocalAppender
    {
        private final StringBuilder builder = new StringBuilder();
        private final char[] threadName;
        private char[] buffer;

        PrintingThreadLocalAppender()
        {
            this.threadName = (":" + DebugLogger.threadName()).toCharArray();
            buffer = new char[threadName.length + DEFAULT_BUFFER_SIZE];
        }

        @Override
        public void log(final LogTag tag, final StringBuilder stringBuilder)
        {
            final int messageLength = stringBuilder.length();
            final int prefixLength = appendStart(tag, messageLength);
            final char[] buffer = this.buffer;
            stringBuilder.getChars(0, messageLength, buffer, prefixLength);
            output.write(buffer, 0, prefixLength + messageLength);
            output.flush();
        }

        private int appendStart(final LogTag tag, final int messageLength)
        {
            final StringBuilder builder = this.builder;
            builder.setLength(0);
            builder.append(System.currentTimeMillis());
            builder.append(threadName);
            builder.append(tag.logStr());

            final int prefixLength = builder.length();

            final char[] buffer = acquireBuffer(prefixLength + messageLength);
            builder.getChars(0, prefixLength, buffer, 0);
            return prefixLength;
        }

        private char[] acquireBuffer(final int length)
        {
            char[] buffer = this.buffer;
            if (buffer.length < length)
            {
                buffer = new char[length];
                this.buffer = buffer;
            }
            return buffer;
        }
    }

    @Override
    public ThreadLocalAppender makeLocalAppender()
    {
        return new PrintingThreadLocalAppender();
    }
}
