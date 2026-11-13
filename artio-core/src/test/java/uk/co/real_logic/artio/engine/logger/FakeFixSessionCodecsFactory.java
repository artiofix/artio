/*
 * Copyright 2019 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import org.agrona.concurrent.SystemEpochNanoClock;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.EpochFractionFormat;

public class FakeFixSessionCodecsFactory extends FixSessionCodecsFactory
{
    public FakeFixSessionCodecsFactory()
    {
        super(new SystemEpochNanoClock(), EpochFractionFormat.NANOSECONDS);
    }

    @Override
    FixReplayerCodecs get(final long sessionId)
    {
        return new FixReplayerCodecs(FixDictionary.findDefault(), timestampEncoder, new SystemEpochNanoClock());
    }
}
