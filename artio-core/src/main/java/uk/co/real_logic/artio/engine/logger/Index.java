/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

/**
 * Different indexes to be run on the archiver implement this interface.
 *
 * Extends {@link FragmentHandler} so that it can be easily used to replay/catchup
 * a Stream.
 */
public interface Index extends FragmentHandler, AutoCloseable
{
    default String getName()
    {
        return getClass().getSimpleName();
    }

    void close();

    /**
     * Reads the last position that has been indexed.
     *
     * @param consumer a callback that receives each session id and position
     */
    void readLastPosition(IndexedPositionConsumer consumer);

    /**
     * Called on catchup replay on the start. Replay does not create a counter for recording,
     * so it is provided to the method externally.
     *
     * @param buffer containing the data.
     * @param offset at which the data begins.
     * @param length of the data in bytes.
     * @param header representing the meta data for the data.
     * @param recordingId id of replayed recording
     */
    void onCatchup(DirectBuffer buffer, int offset, int length, Header header, long recordingId);

    /**
     * Optional method to perform some period work on the index, eg compaction or updating another system.
     *
     * @return amount of work done.
     */
    default int doWork()
    {
        return 0;
    }
}
