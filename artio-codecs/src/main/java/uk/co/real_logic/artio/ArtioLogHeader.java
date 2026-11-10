/*
 * Copyright 2021 Monotonic Limited.
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

/**
 * Header object that represents information about a message in a stream that has been scanner using either the
 * <code>FixMessageLogger</code> or <code>FixArchiveScanner</code>.
 */
public class ArtioLogHeader
{
    private final int streamId;

    public ArtioLogHeader(final int streamId)
    {
        this.streamId = streamId;
    }

    /**
     * The stream id of the message that was originally archived.
     *
     * @return the stream id of the message
     */
    public int streamId()
    {
        return streamId;
    }

    @Override
    public String toString()
    {
        return "ArtioLogHeader{" +
            "streamId=" + streamId +
            '}';
    }
}
