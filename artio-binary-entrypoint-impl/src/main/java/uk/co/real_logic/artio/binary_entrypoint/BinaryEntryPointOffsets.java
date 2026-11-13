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
package uk.co.real_logic.artio.binary_entrypoint;

import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.fixp.AbstractFixPOffsets;

public class BinaryEntryPointOffsets extends AbstractFixPOffsets
{

    @Override
    public int seqNumOffset(final int templateId)
    {
        return 0;
    }

    @Override
    public int seqNum(final int templateId, final DirectBuffer buffer, final int messageOffset)
    {
        return 0;
    }

    @Override
    public int possRetransOffset(final int templateId)
    {
        return 0;
    }

    @Override
    public int possRetrans(final int templateId, final DirectBuffer buffer, final int messageOffset)
    {
        return 0;
    }

    @Override
    public int sendingTimeEpochOffset(final int templateId)
    {
        return 0;
    }
}
