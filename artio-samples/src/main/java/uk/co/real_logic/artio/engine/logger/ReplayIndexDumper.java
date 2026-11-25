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

import uk.co.real_logic.artio.CommandLineUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.logger.ReplayIndexExtractor.ReplayIndexHandler;
import uk.co.real_logic.artio.engine.logger.ReplayIndexExtractor.StartPositionExtractor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Prints out the state of a replay index file.
 */
public final class ReplayIndexDumper
{
    private static final String USAGE =
        "Usage: ReplayIndexDumper <replay index header file> <fix session id> <stream id>";

    public static void main(final String[] args) throws IOException
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(USAGE);
        }

        final File headerFile = new File(args[0]);
        final long fixSessionId = CommandLineUtil.parseLong(args[1], "fix session id");
        final int streamId = CommandLineUtil.parseInt(args[2], "stream id");
        final String logFileDir = headerFile.getParent();

        final Consumer<ReplayIndexHandler> extract = handler ->
        {
            ReplayIndexExtractor.extract(
                headerFile,
                EngineConfiguration.DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
                EngineConfiguration.DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY,
                fixSessionId,
                streamId,
                logFileDir,
                handler);
        };

        final StartPositionExtractor positionExtractor = new StartPositionExtractor();
        extract.accept(positionExtractor);
        System.out.println("positionExtractor.highestSequenceIndex() = " + positionExtractor.highestSequenceIndex());
        System.out.println("positionExtractor.recordingIdToStartPosition() = " +
            positionExtractor.recordingIdToStartPosition());

        final String output = "replay-index-dump.csv";
        try (BufferedWriter out = new BufferedWriter(new FileWriter(output)))
        {
            extract.accept(new ReplayIndexExtractor.PrintError(out));
        }
    }
}
