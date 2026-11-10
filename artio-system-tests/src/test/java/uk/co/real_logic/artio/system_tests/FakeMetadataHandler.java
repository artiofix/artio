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
package uk.co.real_logic.artio.system_tests;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.library.MetadataHandler;
import uk.co.real_logic.artio.messages.MetaDataStatus;

class FakeMetadataHandler implements MetadataHandler
{
    boolean callbackReceived = false;

    private MetaDataStatus status;
    private UnsafeBuffer buffer;

    @Override
    public void onMetaData(
        final long sessionId,
        final MetaDataStatus status,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        this.buffer = new UnsafeBuffer(new byte[length]);
        this.buffer.putBytes(0, buffer, offset, length);
        this.status = status;
        callbackReceived = true;
    }

    public boolean callbackReceived()
    {
        return callbackReceived;
    }

    public UnsafeBuffer buffer()
    {
        return buffer;
    }

    public MetaDataStatus status()
    {
        return status;
    }
}
