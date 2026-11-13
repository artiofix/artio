/*
 * Copyright 2020 Adaptive Financial Consulting Ltd.
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

import org.agrona.IoUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.storage.messages.EngineInformationDecoder;
import uk.co.real_logic.artio.storage.messages.EngineInformationEncoder;
import uk.co.real_logic.artio.storage.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.storage.messages.MessageHeaderEncoder;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class DuplicateEngineChecker implements Agent
{
    static final String FILE_NAME = "engine-info";

    private final EngineInformationEncoder engineInformation = new EngineInformationEncoder();
    private final UnsafeBuffer buffer = new UnsafeBuffer();

    private final long duplicateEngineTimeoutInMs;
    private final File file;
    private final String logFileDir;
    private final boolean errorIfDuplicateEngineDetected;

    private MappedByteBuffer mappedByteBuffer;
    private long nextDeadlineInMs;

    public DuplicateEngineChecker(
        final long duplicateEngineTimeoutInMs, final String logFileDir, final boolean errorIfDuplicateEngineDetected)
    {
        this.duplicateEngineTimeoutInMs = duplicateEngineTimeoutInMs;
        this.file = new File(logFileDir, FILE_NAME);
        this.logFileDir = logFileDir;
        this.errorIfDuplicateEngineDetected = errorIfDuplicateEngineDetected;
    }

    public void check()
    {
        if (file.exists())
        {
            mappedByteBuffer = IoUtil.mapExistingFile(file, FILE_NAME);
            try
            {
                buffer.wrap(mappedByteBuffer);

                final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
                final EngineInformationDecoder engineInformationDecoder = new EngineInformationDecoder();
                messageHeaderDecoder.wrap(buffer, 0);
                engineInformationDecoder.wrap(
                    buffer,
                    MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                final long heartbeatTimeInMs = engineInformationDecoder.heartbeatTimeInMs();
                final String otherRuntimeName = engineInformationDecoder.runtimeName();

                final long latestEngineHeartbeatInMs = System.currentTimeMillis() - duplicateEngineTimeoutInMs;
                if (heartbeatTimeInMs > latestEngineHeartbeatInMs && errorIfDuplicateEngineDetected)
                {
                    throw new IllegalStateException(String.format(
                        "Error starting Engine a duplicate Artio Engine instance might be running [%s] produced " +
                        "heartbeat at %d, less than %d ms ago.",
                        otherRuntimeName,
                        heartbeatTimeInMs,
                        duplicateEngineTimeoutInMs));
                }
            }
            finally
            {
                IoUtil.unmap(mappedByteBuffer);
            }
        }

        // Use move with a temp file to ensure an atomic replacement of the old file if it has expired
        final File tempFile = new File(logFileDir, UUID.randomUUID().toString() + "-" + FILE_NAME);

        final byte[] thisRuntimeName = ManagementFactory.getRuntimeMXBean().getName().getBytes(StandardCharsets.UTF_8);
        final long length = MessageHeaderEncoder.ENCODED_LENGTH + EngineInformationEncoder.BLOCK_LENGTH +
            EngineInformationEncoder.runtimeNameHeaderLength() + thisRuntimeName.length;

        mappedByteBuffer = IoUtil.mapNewFile(tempFile, length);
        buffer.wrap(mappedByteBuffer);

        engineInformation
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .putRuntimeName(thisRuntimeName, 0, thisRuntimeName.length);

        saveheartbeatTime(System.currentTimeMillis());

        mappedByteBuffer.force();

        try
        {
            Files.move(tempFile.toPath(), file.toPath(), REPLACE_EXISTING);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int doWork()
    {
        final long currentTimeInMs = System.currentTimeMillis();
        if (currentTimeInMs > nextDeadlineInMs)
        {
            saveheartbeatTime(currentTimeInMs);
            return 1;
        }

        return 0;
    }

    @Override
    public String roleName()
    {
        return "DuplicateEngineChecker";
    }

    private void saveheartbeatTime(final long currentTimeInMs)
    {
        engineInformation.heartbeatTimeInMs(currentTimeInMs);
        nextDeadlineInMs = currentTimeInMs + duplicateEngineTimeoutInMs;
    }

    public void finalClose()
    {
        unmap();
        if (file.exists())
        {
            file.delete();
        }
    }

    void unmap()
    {
        if (mappedByteBuffer != null)
        {
            IoUtil.unmap(mappedByteBuffer);
            IoUtil.unmap(buffer.byteBuffer());
        }
    }
}
