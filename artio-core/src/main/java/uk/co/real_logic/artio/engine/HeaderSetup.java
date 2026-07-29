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
package uk.co.real_logic.artio.engine;

import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;

public final class HeaderSetup
{
    public static void setup(final SessionHeaderDecoder reqHeader, final SessionHeaderEncoder respHeader)
    {
        respHeader.targetCompID(reqHeader.senderCompID(), reqHeader.senderCompIDLength());
        respHeader.senderCompID(reqHeader.targetCompID(), reqHeader.targetCompIDLength());
        if (reqHeader.hasSenderLocationID())
        {
            respHeader.targetLocationID(reqHeader.senderLocationID(), reqHeader.senderLocationIDLength());
        }
        if (reqHeader.hasSenderSubID())
        {
            respHeader.targetSubID(reqHeader.senderSubID(), reqHeader.senderSubIDLength());
        }
        if (reqHeader.hasTargetLocationID())
        {
            respHeader.senderLocationID(reqHeader.targetLocationID(), reqHeader.targetLocationIDLength());
        }
        if (reqHeader.hasTargetSubID())
        {
            respHeader.senderSubID(reqHeader.targetSubID(), reqHeader.targetSubIDLength());
        }
    }
}
