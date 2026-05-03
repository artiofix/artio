/*
 * Copyright 2021 Monotonic Ltd.
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

import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.HeaderEncoder;
import uk.co.real_logic.artio.decoder.HeaderDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;

import static uk.co.real_logic.artio.dictionary.SessionConstants.LOGON_MESSAGE_TYPE_STR;

public class SessionContextCreator
{
    public static void main(final String[] args)
    {
        final String[][] compids = {};

        final String fileName = args[0];
        final File bufferFile = new File(fileName);
        if (bufferFile.exists())
        {
            bufferFile.delete();
        }

        final int bigBuffer = 4 * 1024 * 1024;
        final MappedFile mappedFile = MappedFile.map(bufferFile, bigBuffer);
        final SessionIdStrategy idStrategy = SessionIdStrategy.senderAndTarget();
        final FixContexts contexts = new FixContexts(
            mappedFile,
            idStrategy,
            1,
            throwable -> throwable.printStackTrace(System.out),
            false,
            new IncrementalSessionIdGenerator());

        final FixDictionary dictionary = FixDictionary.of(FixDictionary.findDefault());
        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[1024]);

        for (final String[] compIdPair : compids)
        {
            final String localCompId = compIdPair[0];
            final String remoteCompId = compIdPair[1];

            System.out.println("localCompId = " + localCompId + ", remoteCompId = " + remoteCompId);

            final HeaderEncoder encoder = new HeaderEncoder()
                .senderCompID(remoteCompId)
                .targetCompID(localCompId)
                .msgType(LOGON_MESSAGE_TYPE_STR)
                .sendingTime(new byte[1]);

            final long result = encoder.startMessage(buffer, 0);
            final int length = Encoder.length(result);
            final int offset = Encoder.offset(result);

            final HeaderDecoder decoder = new HeaderDecoder();
            decoder.reset();
            decoder.decode(buffer, offset, length);

            final CompositeKey compositeKey = idStrategy.onAcceptLogon(decoder);
            final SessionContext sessionContext = contexts.newSessionContext(compositeKey, dictionary);
            System.out.println("sessionContext = " + sessionContext.sessionId());
        }

        SessionContextDumper.main(new String[] { fileName});
    }
}
