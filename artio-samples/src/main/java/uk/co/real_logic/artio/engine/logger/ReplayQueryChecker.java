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
package uk.co.real_logic.artio.engine.logger;

import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.LogTag;

import java.io.IOException;

import static uk.co.real_logic.artio.engine.EngineConfiguration.*;

/**
 * Allows reproduction of replay query issues
 */
public final class ReplayQueryChecker
{
    public static void main(final String[] args) throws IOException
    {
        final String logFileDir = args[0];
        final long sessionId = Long.parseLong(args[1]);
        final int requiredStreamId = Integer.parseInt(args[2]);
        final int endSequenceNumber = Integer.parseInt(args[3]);

        final ReplayQuery query = new ReplayQuery(
            logFileDir,
            DEFAULT_LOGGER_CACHE_NUM_SETS,
            DEFAULT_LOGGER_CACHE_SET_SIZE,
            LoggerUtil::mapExistingFile,
            requiredStreamId,
            CommonConfiguration.backoffIdleStrategy(),
            null,
            Throwable::printStackTrace,
            NoOpReplayQueryListener.INSTANCE,
            -1,
            DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
            DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY);

        query.query(
            sessionId,
            1,
            0,
            endSequenceNumber,
            0,
            LogTag.REPLAY,
            null);
    }
}
