/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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

import io.aeron.logbuffer.Header;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.storage.messages.ReplayIndexRecordEncoder;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import static io.aeron.archive.status.RecordingPos.NULL_RECORDING_ID;
import static io.aeron.logbuffer.FrameDescriptor.*;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.SequenceNumberExtractor.NO_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.*;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.*;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;

/**
 * Builds an index of a composite key of session id and sequence number for a given stream.
 *
 * Written Positions are stored in a separate file at {@link ReplayIndexDescriptor#replayPositionPath(String, int)}.
 *
 * Buffer Consists of:
 *
 * MessageHeader
 * Head position counter
 * Tail position counter
 * Multiple ReplayIndexRecord entries
 */
public class ReplayIndex implements Index
{
    private static final long NO_TIMESTAMP = -1;

    private final LongFunction<SessionIndex> newSessionIndex = SessionIndex::new;
    private final MessageHeaderDecoder frameHeaderDecoder = new MessageHeaderDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final ThrottleNotificationDecoder throttleNotification = new ThrottleNotificationDecoder();
    private final ThrottleRejectDecoder throttleReject = new ThrottleRejectDecoder();
    private final ResetSequenceNumberDecoder resetSequenceNumber = new ResetSequenceNumberDecoder();
    private final RedactSequenceUpdateDecoder redactSequenceUpdateDecoder = new RedactSequenceUpdateDecoder();
    private final ReplayIndexRecordEncoder replayIndexRecord = new ReplayIndexRecordEncoder();
    private final MessageHeaderEncoder indexHeaderEncoder = new MessageHeaderEncoder();

    private final IndexedPositionWriter positionWriter;
    private final IndexedPositionReader positionReader;
    private final SequenceNumberExtractor sequenceNumberExtractor;

    private final FixPSequenceIndexer fixPSequenceIndexer;

    private final Long2ObjectHashMap<SessionIndex> fixSessionIdToIndex;

    private final String logFileDir;
    private final int requiredStreamId;
    private final long indexFileSize;
    private final int segmentSize;
    private final ReplayEvictionHandler evictionHandler;
    private final int segmentSizeBitShift;
    private final int segmentCount;
    private final BufferFactory bufferFactory;
    private final AtomicBuffer positionBuffer;
    private final ErrorHandler errorHandler;
    private final RecordingIdLookup recordingIdLookup;
    private final TimeIndexWriter timeIndex;
    private final SessionOwnershipTracker sessTracker;

    public ReplayIndex(
        final SequenceNumberExtractor sequenceNumberExtractor,
        final String logFileDir,
        final int requiredStreamId,
        final int indexFileCapacity,
        final int indexSegmentCapacity,
        final BufferFactory bufferFactory,
        final AtomicBuffer positionBuffer,
        final ErrorHandler errorHandler,
        final RecordingIdLookup recordingIdLookup,
        final Long2LongHashMap connectionIdToFixPSessionId,
        final FixPProtocolType fixPProtocolType,
        final SequenceNumberIndexReader reader,
        final long timeIndexReplayFlushIntervalInNs,
        final boolean indexChecksumEnabled,
        final ReplayEvictionHandler evictionHandler)
    {
        this.sequenceNumberExtractor = sequenceNumberExtractor;
        this.logFileDir = logFileDir;
        this.requiredStreamId = requiredStreamId;
        this.indexFileSize = ReplayIndexDescriptor.capacityToBytes(indexFileCapacity);
        this.segmentSize = ReplayIndexDescriptor.capacityToBytesInt(indexSegmentCapacity);
        this.evictionHandler = evictionHandler;
        this.segmentSizeBitShift = Long.numberOfTrailingZeros(segmentSize);
        this.segmentCount = ReplayIndexDescriptor.segmentCount(indexFileCapacity, indexSegmentCapacity);
        this.bufferFactory = bufferFactory;
        this.positionBuffer = positionBuffer;
        this.errorHandler = errorHandler;
        this.recordingIdLookup = recordingIdLookup;

        checkPowerOfTwo("segmentCount", segmentCount);
        checkPowerOfTwo("segmentSize", segmentSize);
        checkPowerOfTwo("indexFileSize", indexFileSize);

        sessTracker = new SessionOwnershipTracker();
        fixPSequenceIndexer = new FixPSequenceIndexer(
            connectionIdToFixPSessionId, errorHandler, fixPProtocolType, reader,
            (sequenceNumber, uuid, messageSize, endPosition, aeronSessionId, possRetrans, timestamp, forNextSession) ->
                onFixPSequenceUpdate(sequenceNumber, uuid, messageSize, endPosition, aeronSessionId, forNextSession));
        checkIndexRecordCapacity(indexFileCapacity);
        fixSessionIdToIndex = new Long2ObjectHashMap<>();
        final String replayPositionPath = replayPositionPath(logFileDir, requiredStreamId);
        positionWriter = new IndexedPositionWriter(
            positionBuffer, errorHandler, 0, replayPositionPath, recordingIdLookup, indexChecksumEnabled);
        positionReader = new IndexedPositionReader(positionBuffer);
        timeIndex = new TimeIndexWriter(
            logFileDir, requiredStreamId, timeIndexReplayFlushIntervalInNs, errorHandler);
    }

    private void checkPowerOfTwo(final String name, final int value)
    {
        if (!BitUtil.isPowerOfTwo(value))
        {
            throw new IllegalStateException(
                "segmentCount must be a positive power of 2: " + name + "=" + value);
        }
    }

    private void checkPowerOfTwo(final String name, final long value)
    {
        if (!BitUtil.isPowerOfTwo(value))
        {
            throw new IllegalStateException(
                "segmentCount must be a positive power of 2: " + name + "=" + value);
        }
    }

    private void onFixPSequenceUpdate(
        final int sequenceNumber,
        final long sessionId,
        final int messageSize,
        final long endPosition,
        final int aeronSessionId,
        final boolean forNextSession)
    {
        if (sequenceNumber == 0)
        {
            onFixPResetSequenceNumber(sessionId, forNextSession);
        }
        else
        {
            final SessionIndex sessionIndex = sessionIndex(sessionId);
            sessionIndex.checkForNextSession(forNextSession);
            sessionIndex
                .onRecord(endPosition, messageSize, sequenceNumber, 0, aeronSessionId, NULL_RECORDING_ID, 0);
        }
    }

    private void onFixPResetSequenceNumber(final long sessionId, final boolean forNextSession)
    {
        final SessionIndex sessionIndex = fixSessionIdToIndex.get(sessionId);
        if (sessionIndex != null)
        {
            if (forNextSessionVersion(sessionIndex.headerBuffer))
            {
                flipNextSession(forNextSession, sessionIndex.headerBuffer);
                return;
            }
        }
        else
        {
            // This session isn't in the cache
            final File headerFile = replayIndexHeaderFile(sessionId);
            if (headerFile.exists())
            {
                final UnsafeBuffer headerBuffer = mapUnsafeBuffer(HEADER_FILE_SIZE, headerFile);
                try
                {
                    if (forNextSessionVersion(headerBuffer))
                    {
                        flipNextSession(forNextSession, headerBuffer);
                        return;
                    }
                }
                finally
                {
                    IoUtil.unmap(headerBuffer.byteBuffer());
                }
            }
        }

        onResetSequenceNumber(sessionId);
    }

    private void flipNextSession(final boolean forNextSession, final UnsafeBuffer headerBuffer)
    {
        if (!forNextSession)
        {
            // We've logged back in after ForNextSessionVerId messages have been written into the index,
            // So we need to reset this flag to indicate that future messages aren't like that
            notForNextSession(headerBuffer);
        }
    }

    private long continuedFixSessionId;
    private int continuedSequenceNumber;
    private int continuedSequenceIndex;
    private long continuedTimestamp;

    public void onCatchup(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header,
        final long recordingId)
    {
        onFragment(buffer, offset, length, header, recordingId);
    }

    public void onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        final int streamId = header.streamId();
        if (streamId == requiredStreamId)
        {
            onFragment(buffer, offset, length, header, NULL_RECORDING_ID);
        }
    }

    public void onFragment(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final Header header,
        final long recordingId)
    {
        final long endPosition = header.position();
        final byte flags = header.flags();
        final int length = BitUtil.align(srcLength, FRAME_ALIGNMENT);

        int offset = srcOffset;
        frameHeaderDecoder.wrap(srcBuffer, offset);
        final int templateId = frameHeaderDecoder.templateId();
        final int blockLength = frameHeaderDecoder.blockLength();
        final int version = frameHeaderDecoder.version();
        offset += frameHeaderDecoder.encodedLength();

        final boolean beginMessage = (flags & BEGIN_FRAG_FLAG) == BEGIN_FRAG_FLAG;
        final int aeronSessionId = header.sessionId();

        if ((flags & UNFRAGMENTED) == UNFRAGMENTED || beginMessage)
        {
            switch (templateId)
            {
                case FixMessageEncoder.TEMPLATE_ID:
                {
                    messageFrame.wrap(srcBuffer, offset, blockLength, version);
                    if (!sessTracker.messageFromWrongLibrary(messageFrame.session(), messageFrame.libraryId()))
                    {
                        onFixMessage(
                            srcBuffer, header, recordingId, endPosition,
                            length, offset, blockLength, version, beginMessage);
                    }
                    break;
                }

                case ThrottleNotificationDecoder.TEMPLATE_ID:
                {
                    throttleNotification.wrap(srcBuffer, offset, blockLength, version);
                    final int sequenceNumber = throttleNotification.refSeqNum();
                    final long fixSessionId = throttleNotification.session();
                    final int sequenceIndex = throttleNotification.sequenceIndex();
                    if (!sessTracker.messageFromWrongLibrary(fixSessionId, throttleNotification.libraryId()))
                    {
                        sessionIndex(fixSessionId).onRecord(
                            endPosition, length, sequenceNumber, sequenceIndex, aeronSessionId, recordingId,
                            NO_TIMESTAMP);
                    }
                    break;
                }

                case ThrottleRejectDecoder.TEMPLATE_ID:
                {
                    throttleReject.wrap(srcBuffer, offset, blockLength, version);
                    final int sequenceNumber = throttleReject.sequenceNumber();
                    final long fixSessionId = throttleReject.session();
                    final int sequenceIndex = throttleReject.sequenceIndex();
                    if (!sessTracker.messageFromWrongLibrary(fixSessionId, throttleReject.libraryId()))
                    {
                        sessionIndex(fixSessionId).onRecord(
                            endPosition, length, sequenceNumber, sequenceIndex, aeronSessionId, recordingId,
                            NO_TIMESTAMP);
                    }
                    break;
                }

                case FixPMessageDecoder.TEMPLATE_ID:
                case ILinkConnectDecoder.TEMPLATE_ID:
                case FollowerSessionRequestDecoder.TEMPLATE_ID:
                    fixPSequenceIndexer.onFragment(srcBuffer, srcOffset, srcLength, header);
                    break;

                case ResetSequenceNumberDecoder.TEMPLATE_ID:
                {
                    resetSequenceNumber.wrap(srcBuffer, offset, blockLength, version);
                    final long fixSessionId = resetSequenceNumber.session();
                    onResetSequenceNumber(fixSessionId);
                    break;
                }

                case RedactSequenceUpdateDecoder.TEMPLATE_ID:
                {
                    redactSequenceUpdateDecoder.wrap(srcBuffer, offset, blockLength, version);
                    onRedactSequenceUpdateDecoder();
                    break;
                }

                case ManageSessionDecoder.TEMPLATE_ID:
                {
                    sessTracker.onManageSession(srcBuffer, offset, blockLength, version);
                    break;
                }
            }
        }
        else
        {
            sessionIndex(continuedFixSessionId).onRecord(
                endPosition, length,
                continuedSequenceNumber, continuedSequenceIndex, aeronSessionId, recordingId, continuedTimestamp);
        }

        positionWriter.update(aeronSessionId, templateId, endPosition, recordingId);
        positionWriter.updateChecksums();
    }

    private void onRedactSequenceUpdateDecoder()
    {
        // We only update the replay index in response to a redact if it is used to redact all the sequence
        // numbers within the index
        final long fixSessionId = redactSequenceUpdateDecoder.session();
        final int sequenceNumber = redactSequenceUpdateDecoder.correctSequenceNumber();
        if (sequenceNumber <= 1)
        {
            onResetSequenceNumber(fixSessionId);
        }

        fixPSequenceIndexer.onRedactSequenceUpdate(fixSessionId, sequenceNumber);
    }

    private void onFixMessage(
        final DirectBuffer srcBuffer,
        final Header header,
        final long recordingId,
        final long endPosition,
        final int length,
        final int start,
        final int blockLength,
        final int version,
        final boolean beginMessage)
    {
        if (messageFrame.status() == OK)
        {
            int offset = start + blockLength;
            if (version >= metaDataSinceVersion())
            {
                offset += metaDataHeaderLength() + messageFrame.metaDataLength();
                messageFrame.skipMetaData();
            }
            offset += bodyHeaderLength();

            final long fixSessionId = messageFrame.session();
            sequenceNumberExtractor.extractCached(
                srcBuffer, offset, messageFrame.bodyLength(), header.sessionId(), endPosition);
            int sequenceNumber = sequenceNumberExtractor.sequenceNumber();
            final int newSequenceNumber = sequenceNumberExtractor.newSequenceNumber();
            final int sequenceIndex = messageFrame.sequenceIndex();
            final long timestamp = messageFrame.timestamp();
            final long messageType = messageFrame.messageType();

            if (sequenceNumber != NO_SEQUENCE_NUMBER)
            {
                if (beginMessage)
                {
                    continuedFixSessionId = fixSessionId;
                    continuedSequenceNumber = sequenceNumber;
                    continuedSequenceIndex = sequenceIndex;
                    continuedTimestamp = timestamp;
                }

                final SessionIndex sessionIndex = sessionIndex(fixSessionId);
                final int aeronSessionId = header.sessionId();

                if (newSequenceNumber > sequenceNumber)
                {
                    // implies newSequenceNumber != NO_SEQUENCE_NUMBER
                    while (sequenceNumber < newSequenceNumber)
                    {
                        sessionIndex.onRecord(
                            endPosition, length, sequenceNumber, sequenceIndex, aeronSessionId, recordingId, timestamp);
                        sequenceNumber++;
                    }
                }
                else
                {
                    if (messageType == SEQUENCE_RESET_MESSAGE_TYPE && newSequenceNumber == 1)
                    {
                        sequenceNumber = 0;
                    }

                    sessionIndex.onRecord(
                        endPosition, length, sequenceNumber, sequenceIndex, aeronSessionId, recordingId, timestamp);
                }
            }
        }
    }

    private void onResetSequenceNumber(final long fixSessionId)
    {
        final SessionIndex index = fixSessionIdToIndex.remove(fixSessionId);

        if (index != null)
        {
            index.reset();
        }
        else
        {
            // File might be present but not within the cache.
            evictionHandler.onReset(fixSessionId);
            final File headerFile = replayIndexHeaderFile(fixSessionId);
            if (headerFile.exists())
            {
                deleteFile(headerFile);
            }
            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++)
            {
                final File segmentFile = replayIndexSegmentFile(fixSessionId, segmentIndex);
                if (segmentFile.exists())
                {
                    deleteFile(segmentFile);
                }
            }
        }
    }

    private SessionIndex sessionIndex(final long fixSessionId)
    {
        return fixSessionIdToIndex
            .computeIfAbsent(fixSessionId, newSessionIndex);
    }

    public int doWork()
    {
        return positionWriter.checkRecordings() + timeIndex.doWork();
    }

    public void close()
    {
        Exceptions.closeAll(
            timeIndex,
            positionWriter);
        fixSessionIdToIndex.values().forEach(SessionIndex::close);
        fixSessionIdToIndex.clear();
        IoUtil.unmap(positionBuffer.byteBuffer());
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
        positionReader.readLastPosition(consumer);
    }

    public AtomicBuffer replayIndexPositionBuffer()
    {
        return positionWriter.buffer();
    }

    private final class SessionIndex implements AutoCloseable
    {
        private final long fixSessionId;
        private final int segmentSize;
        private final int segmentSizeBitShift;

        private final UnsafeBuffer headerBuffer;
        private final File headerFile;

        private final UnsafeBuffer[] segmentBuffers;
        private final File[] segmentBufferFiles;

        SessionIndex(final long fixSessionId)
        {
            final ReplayIndex replayIndex = ReplayIndex.this;

            this.fixSessionId = fixSessionId;
            this.segmentSize = replayIndex.segmentSize;
            this.segmentSizeBitShift = replayIndex.segmentSizeBitShift;
            segmentBuffers = new UnsafeBuffer[segmentCount];
            segmentBufferFiles = new File[segmentCount];

            headerFile = replayIndexHeaderFile(fixSessionId);
            final boolean exists = headerFile.exists();
            this.headerBuffer = mapUnsafeBuffer(HEADER_FILE_SIZE, headerFile);

            if (!exists)
            {
                final ReplayIndexRecordEncoder replayIndexRecord = replayIndex.replayIndexRecord;
                final MessageHeaderEncoder indexHeaderEncoder = replayIndex.indexHeaderEncoder;
                indexHeaderEncoder
                    .wrap(headerBuffer, 0)
                    .blockLength(replayIndexRecord.sbeBlockLength())
                    .templateId(replayIndexRecord.sbeTemplateId())
                    .schemaId(replayIndexRecord.sbeSchemaId())
                    .version(replayIndexRecord.sbeSchemaVersion());
                notForNextSession(headerBuffer);

                checkSegmentFilesDoNotExist();
            }
            else
            {
                // Reset the positions in order to avoid wraps at the start.
                final long resetPosition = beginChange(headerBuffer);
                endChangeOrdered(headerBuffer, resetPosition);
            }
        }

        private void checkSegmentFilesDoNotExist()
        {
            final List<File> existingSegmentFiles = new ArrayList<>();

            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++)
            {
                final File segmentFile = replayIndexSegmentFile(fixSessionId, segmentIndex);
                if (segmentFile.exists())
                {
                    existingSegmentFiles.add(segmentFile);
                }
            }

            if (!existingSegmentFiles.isEmpty())
            {
                errorHandler.onError(new IllegalStateException("Replay index header file did not exist, " +
                    "but the following segment files did: " + existingSegmentFiles));
            }
        }

        void onRecord(
            final long endPosition,
            final int length,
            final int sequenceNumber,
            final int sequenceIndex,
            final int aeronSessionId,
            final long knownRecordingId,
            final long timestamp)
        {
            final long beginChangePosition = beginChange(headerBuffer);
            final long changePosition = beginChangePosition + RECORD_LENGTH;
            final long recordingId = knownRecordingId ==
                NULL_RECORDING_ID ? recordingIdLookup.getRecordingId(aeronSessionId) : knownRecordingId;
            final long beginPosition = endPosition - length;

            beginChangeOrdered(headerBuffer, changePosition);
            VarHandle.storeStoreFence();

            final int segmentIndex = ReplayIndexDescriptor.segmentIndex(
                beginChangePosition, segmentSizeBitShift, indexFileSize);
            final UnsafeBuffer segmentBuffer = segmentBuffer(segmentIndex);
            final int offset = offsetInSegment(beginChangePosition, segmentSize);

            replayIndexRecord
                .wrap(segmentBuffer, offset)
                .position(beginPosition)
                .sequenceNumber(sequenceNumber)
                .sequenceIndex(sequenceIndex)
                .recordingId(recordingId)
                .length(length);

            endChangeOrdered(headerBuffer, changePosition);

            if (timestamp != NO_TIMESTAMP)
            {
                timeIndex.onRecord(recordingId, endPosition, timestamp);
            }
        }

        private UnsafeBuffer segmentBuffer(final int segmentIndex)
        {
            UnsafeBuffer segmentBuffer = segmentBuffers[segmentIndex];
            if (segmentBuffer == null)
            {
                final File file = replayIndexSegmentFile(fixSessionId, segmentIndex);
                segmentBufferFiles[segmentIndex] = file;
                segmentBuffer = mapUnsafeBuffer(segmentSize, file);
                segmentBuffers[segmentIndex] = segmentBuffer;
            }
            return segmentBuffer;
        }

        void reset()
        {
            close();

            evictionHandler.onReset(fixSessionId);
            deleteFile(headerFile);

            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++)
            {
                final File segmentFile = replayIndexSegmentFile(fixSessionId, segmentIndex);
                if (segmentFile.exists())
                {
                    deleteFile(segmentFile);
                }
            }
        }

        public void close()
        {
            ReplayIndexDescriptor.unmapBuffers(headerBuffer, segmentBuffers);
        }

        public void checkForNextSession(final boolean forNextSession)
        {
            if (forNextSession && !forNextSessionVersion(headerBuffer))
            {
                forNextSessionVersion(headerBuffer, true);
            }
        }
    }

    static void notForNextSession(final UnsafeBuffer headerBuffer)
    {
        forNextSessionVersion(headerBuffer, false);
    }

    private File replayIndexHeaderFile(final long fixSessionId)
    {
        return ReplayIndexDescriptor.replayIndexHeaderFile(logFileDir, fixSessionId, requiredStreamId);
    }

    private File replayIndexSegmentFile(final long fixSessionId, final int segmentIndex)
    {
        return ReplayIndexDescriptor.replayIndexSegmentFile(logFileDir, fixSessionId, requiredStreamId, segmentIndex);
    }

    void deleteFile(final File replayIndexFile)
    {
        if (!replayIndexFile.delete())
        {
            errorHandler.onError(new IOException("Unable to delete replay index file: " + replayIndexFile));
        }
    }

    private UnsafeBuffer mapUnsafeBuffer(final int size, final File replayIndexFile)
    {
        return new UnsafeBuffer(bufferFactory.map(replayIndexFile, size));
    }
}
