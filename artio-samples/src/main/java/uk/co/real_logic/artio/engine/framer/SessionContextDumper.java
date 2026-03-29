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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.session.SessionIdStrategy;

import java.io.File;
import java.util.Comparator;

/**
 * Prints out the state of a session contexts file
 */
public final class SessionContextDumper
{
    public static void main(final String[] args)
    {
        final String fileName = args[0];
        final File bufferFile = new File(fileName);
        final MappedFile mappedFile = MappedFile.map(bufferFile, (int)bufferFile.length());
        final FixContexts contexts = new FixContexts(
            mappedFile,
            SessionIdStrategy.senderAndTarget(),
            1,
            throwable -> throwable.printStackTrace(System.out),
            false,
            new IncrementalSessionIdGenerator());

        contexts
            .allSessions()
            .stream()
            .sorted(Comparator.comparing(SessionInfo::sessionId))
            .forEach(System.out::println);
    }
}
