/*
 * Copyright 2013 Real Logic Limited.
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

import org.agrona.CloseHelper;
import org.agrona.concurrent.Agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public final class Exceptions
{

    /**
     * Close all closeables in closeables. If any of them throw then throw that exception.
     * If multiple closeables throw an exception when being closed, then throw an exception that contains
     * all of them as suppressed exceptions.
     *
     * @param closeables to be closed.
     */
    public static void closeAll(final List<? extends AutoCloseable> closeables)
    {
        CloseHelper.closeAll(closeables);
    }

    public static void closeAll(final AutoCloseable... closeables)
    {
        CloseHelper.closeAll(closeables);
    }

    public static void closeAll(final Agent... agents)
    {
        closeAll(Stream
            .of(agents)
            .filter(Objects::nonNull)
            .map((agent) -> (AutoCloseable)agent::onClose)
            .collect(toList()));
    }

    public static void suppressingClose(final AutoCloseable closeable, final Exception originalException)
    {
        suppressingClose(closeable, (Throwable)originalException);
    }

    public static void suppressingClose(final AutoCloseable closeable, final Throwable originalThrowable)
    {
        if (closeable != null)
        {
            try
            {
                closeable.close();
            }
            catch (final Exception ex)
            {
                originalThrowable.addSuppressed(ex);
            }
        }
    }

    public static void suppressingCloseAll(final Throwable originalThrowable, final AutoCloseable... closeables)
    {
        if (closeables != null)
        {
            for (final AutoCloseable closeable : closeables)
            {
                suppressingClose(closeable, originalThrowable);
            }
        }
    }

    public static void printStackTrace()
    {
        try
        {
            throw new Exception();
        }
        catch (final Exception ex)
        {
            System.out.println(Thread.currentThread().getName());
            ex.printStackTrace(System.out);
        }
    }

    public static void printStackTracesForAllThreads()
    {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final ThreadInfo[] threads = bean.dumpAllThreads(true, true);
        System.err.println(Arrays.toString(threads));
    }

    public static boolean isJustDisconnect(final Exception ex)
    {
        final String msg = ex.getMessage();
        return msg != null && (msg.contains("Connection reset") ||
               msg.contains("An established connection was aborted"));
    }

    public static String toString(final Throwable throwable)
    {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(bytes))
        {
            throwable.printStackTrace(printStream);
            return bytes.toString();
        }
    }
}
