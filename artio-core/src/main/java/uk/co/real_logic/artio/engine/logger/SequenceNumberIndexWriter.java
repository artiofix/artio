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
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.CollectionUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochClock;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.ChecksumFramer;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.framer.FramerContext;
import uk.co.real_logic.artio.engine.framer.WriteMetaDataResponse;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberDecoder;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static io.aeron.archive.status.RecordingPos.NULL_RECORDING_ID;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static org.agrona.BitUtil.align;
import static uk.co.real_logic.artio.CommonConfiguration.RUNNING_ON_WINDOWS;
import static uk.co.real_logic.artio.engine.SectorFramer.*;
import static uk.co.real_logic.artio.engine.SequenceNumberExtractor.NO_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataSinceVersion;
import static uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder.SCHEMA_VERSION;

/**
 * Writes updates into an in-memory buffer. This buffer is then flushed down to disk. A passing place
 * file is used to ensure that there's a recoverable option if it fails.
 */
public class SequenceNumberIndexWriter implements Index
{
    private static final long MISSING_RECORD = -1L;
    private static final long UNINITIALISED = -1;
    public static final long NO_REQUIRED_POSITION = -1000;

    static final int SEQUENCE_NUMBER_OFFSET = LastKnownSequenceNumberEncoder.sequenceNumberEncodingOffset();
    static final int MESSAGE_POSITION_OFFSET = LastKnownSequenceNumberEncoder.messagePositionEncodingOffset();
    static final int META_DATA_OFFSET = LastKnownSequenceNumberEncoder.metaDataPositionEncodingOffset();
    public static final int RESET_SEQUENCE = 0;

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final ResetSequenceNumberDecoder resetSequenceNumber = new ResetSequenceNumberDecoder();
    private final WriteMetaDataDecoder writeMetaData = new WriteMetaDataDecoder();
    private final RedactSequenceUpdateDecoder redactSequenceUpdate = new RedactSequenceUpdateDecoder();
    private final ThrottleNotificationDecoder throttleNotification = new ThrottleNotificationDecoder();
    private final ThrottleRejectDecoder throttleReject = new ThrottleRejectDecoder();
    private final FollowerSessionReplyDecoder followerSessionReply = new FollowerSessionReplyDecoder();

    private final MessageHeaderDecoder fileHeaderDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder fileHeaderEncoder = new MessageHeaderEncoder();
    private final LastKnownSequenceNumberEncoder lastKnownEncoder = new LastKnownSequenceNumberEncoder();
    private final LastKnownSequenceNumberDecoder lastKnownDecoder = new LastKnownSequenceNumberDecoder();
    private final Long2LongHashMap recordOffsets = new Long2LongHashMap(MISSING_RECORD);

    // Meta data state
    private final File metaDataLocation;
    private final List<WriteMetaDataResponse> responsesToResend = new ArrayList<>();
    private final Predicate<WriteMetaDataResponse> sendResponseFunc = this::sendResponse;
    private final RandomAccessFile metaDataFile;
    private final SequenceNumberIndexReader reader;
    private byte[] metaDataWriteBuffer = new byte[0];
    private final Long2ObjectHashMap<Long2LongHashMap> sessionIdToRedactPositions = new Long2ObjectHashMap<>();

    private final SequenceNumberExtractor sequenceNumberExtractor;
    private FramerContext framerContext;
    private final ChecksumFramer checksumFramer;
    private final AtomicBuffer inMemoryBuffer;
    private final ErrorHandler errorHandler;
    private final Path indexPath;
    private final Path writablePath;
    private final Path passingPlacePath;
    private final int fileCapacity;
    private final int streamId;
    private final int indexedPositionsOffset;
    private final IndexedPositionWriter positionWriter;
    private final FixPSequenceIndexer fixPSequenceIndexer;

    private MappedFile writableFile;
    private MappedFile indexFile;
    private final Long2LongHashMap nextRollPositions = new Long2LongHashMap(UNINITIALISED);

    private final EpochClock clock;
    private final SessionOwnershipTracker sessionOwnershipTracker;
    private final long indexFileStateFlushTimeoutInMs;
    private long lastUpdatedFileTimeInMs;
    private long fileUpdateCount;
    private boolean hasSavedRecordSinceFileUpdate = false;

    public SequenceNumberIndexWriter(
        final SequenceNumberExtractor sequenceNumberExtractor,
        final AtomicBuffer inMemoryBuffer,
        final MappedFile indexFile,
        final ErrorHandler errorHandler,
        final int streamId,
        final RecordingIdLookup recordingIdLookup,
        final long indexFileStateFlushTimeoutInMs,
        final EpochClock clock,
        final String metaDataDir,
        final Long2LongHashMap connectionIdToFixPSessionId,
        final FixPProtocolType fixPProtocolType,
        final boolean indexChecksumEnabled,
        final boolean logMessages)
    {
        this.sequenceNumberExtractor = sequenceNumberExtractor;
        this.inMemoryBuffer = inMemoryBuffer;
        this.indexFile = indexFile;
        this.errorHandler = errorHandler;
        this.streamId = streamId;
        this.fileCapacity = indexFile.buffer().capacity();
        this.indexFileStateFlushTimeoutInMs = indexFileStateFlushTimeoutInMs;
        this.clock = clock;

        this.sessionOwnershipTracker = new SessionOwnershipTracker();
        final String indexFilePath = indexFile.file().getAbsolutePath();
        indexPath = indexFile.file().toPath();
        final File writeableFile = writableFile(indexFilePath);
        writablePath = writeableFile.toPath();
        passingPlacePath = passingFile(indexFilePath).toPath();
        writableFile = MappedFile.map(writeableFile, fileCapacity);

        // TODO: Fsync parent directory
        indexedPositionsOffset = positionTableOffset(fileCapacity);
        checksumFramer = new ChecksumFramer(
            inMemoryBuffer, indexedPositionsOffset, errorHandler, 0, "SequenceNumberIndex",
            indexChecksumEnabled);
        try
        {
            initialiseBuffer();
            if (logMessages)
            {
                positionWriter = new IndexedPositionWriter(
                    positionsBuffer(inMemoryBuffer, indexedPositionsOffset),
                    errorHandler,
                    indexedPositionsOffset,
                    "SequenceNumberIndex",
                    recordingIdLookup, indexChecksumEnabled);
            }
            else
            {
                positionWriter = null;
            }

            if (metaDataDir != null)
            {
                metaDataLocation = metaDataFile(metaDataDir);
                metaDataFile = openMetaDataFile(metaDataLocation);
            }
            else
            {
                metaDataLocation = null;
                metaDataFile = null;
            }
        }
        catch (final Exception e)
        {
            CloseHelper.close(writableFile);
            indexFile.close();
            throw e;
        }

        reader = new SequenceNumberIndexReader(inMemoryBuffer, errorHandler, recordingIdLookup, metaDataDir);
        fixPSequenceIndexer = new FixPSequenceIndexer(
            connectionIdToFixPSessionId, errorHandler, fixPProtocolType, reader,
            (seqNum, uuid, messageSize, endPosition, aeronSessionId, possRetrans, timestamp, forNextSession) ->
                // When possRetrans=true we should only update if the number is actually higher as
                // possRetrans=true messages can be interleaved with normal messages /or/ the last message
                saveRecord(seqNum, uuid, endPosition, NO_REQUIRED_POSITION, possRetrans));
    }

    private RandomAccessFile openMetaDataFile(final File metaDataLocation)
    {
        RandomAccessFile file = null;
        try
        {
            file = new RandomAccessFile(metaDataLocation, "rw");
            if (file.length() == 0)
            {
                writeMetaDataFileHeader(file);
            }
            else
            {
                final long magicNumber = file.readLong();
                final int fileVersion = file.readInt();

                if (magicNumber != META_DATA_MAGIC_NUMBER)
                {
                    throw new IllegalStateException("Invalid magic number in metadata file: " + magicNumber);
                }

                if (fileVersion < READABLE_META_DATA_FILE_VERSION)
                {
                    throw new IllegalStateException("Unreadable metadata file version: " + fileVersion);
                }
            }
            return file;
        }
        catch (final IOException | IllegalStateException e)
        {
            Exceptions.suppressingClose(file, e);
            LangUtil.rethrowUnchecked(e);
        }

        return null;
    }

    private void writeMetaDataFileHeader(final RandomAccessFile file) throws IOException
    {
        file.writeLong(META_DATA_MAGIC_NUMBER);
        file.writeInt(META_DATA_FILE_VERSION);
        file.getFD().sync();
    }

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
        final int srcOffset,
        final int length,
        final Header header)
    {
        final int streamId = header.streamId();
        if (streamId == this.streamId)
        {
            onFragment(buffer, srcOffset, length, header, NULL_RECORDING_ID);
        }
    }

    private void onFragment(
        final DirectBuffer buffer,
        final int srcOffset,
        final int length,
        final Header header,
        final long recordingId)
    {
        final long endPosition = header.position();
        final int aeronSessionId = header.sessionId();

        int offset = srcOffset;
        messageHeader.wrap(buffer, offset);

        offset += messageHeader.encodedLength();
        final int actingBlockLength = messageHeader.blockLength();
        final int version = messageHeader.version();
        final int templateId = messageHeader.templateId();

        if ((header.flags() & BEGIN_FLAG) == BEGIN_FLAG)
        {
            switch (templateId)
            {
                case FixMessageEncoder.TEMPLATE_ID:
                {
                    if (!onFixMessage(buffer, offset, actingBlockLength, version, aeronSessionId, endPosition))
                    {
                        return;
                    }
                    break;
                }

                case ResetSessionIdsDecoder.TEMPLATE_ID:
                {
                    resetSequenceNumbers();
                    break;
                }

                case ResetSequenceNumberDecoder.TEMPLATE_ID:
                {
                    resetSequenceNumber.wrap(buffer, offset, actingBlockLength, version);
                    resetSequenceNumber(resetSequenceNumber.session(), endPosition);
                    break;
                }

                case WriteMetaDataDecoder.TEMPLATE_ID:
                {
                    writeMetaData.wrap(buffer, offset, actingBlockLength, version);
                    onWriteMetaData();
                    break;
                }

                case FollowerSessionReplyDecoder.TEMPLATE_ID:
                {
                    followerSessionReply.wrap(buffer, offset, actingBlockLength, version);
                    onFollowerSessionReply();
                    break;
                }

                case RedactSequenceUpdateDecoder.TEMPLATE_ID:
                {
                    redactSequenceUpdate.wrap(buffer, offset, actingBlockLength, version);
                    onRedactSequenceUpdate();
                    break;
                }

                case ThrottleRejectDecoder.TEMPLATE_ID:
                {
                    throttleReject.wrap(buffer, offset, actingBlockLength, version);
                    if (!onThrottleReject(endPosition))
                    {
                        return;
                    }
                    break;
                }

                case ThrottleNotificationDecoder.TEMPLATE_ID:
                {
                    throttleNotification.wrap(buffer, offset, actingBlockLength, version);
                    if (!onThrottleNotification(endPosition))
                    {
                        return;
                    }
                    break;
                }

                case ManageSessionDecoder.TEMPLATE_ID:
                {
                    sessionOwnershipTracker.onManageSession(buffer, offset, actingBlockLength, version);
                    break;
                }

                default:
                {
                    fixPSequenceIndexer.onFragment(buffer, srcOffset, length, header);
                    break;
                }
            }
        }

        checkTermRoll(aeronSessionId, buffer, srcOffset, endPosition, length);
        if (positionWriter != null)
        {
            positionWriter.update(aeronSessionId, templateId, endPosition, recordingId);
        }
    }

    private void onFollowerSessionReply()
    {
        // create an entry here for a follower session so that metadata can be saved before
        final long sessionId = followerSessionReply.session();
        if (reader.lastKnownSequenceNumber(sessionId) == UNK_SESSION)
        {
            onRedactSequenceUpdate(0, sessionId, NO_REQUIRED_POSITION);
        }
    }

    private boolean onThrottleReject(final long position)
    {
        final ThrottleRejectDecoder throttleReject = this.throttleReject;
        final long sessionId = throttleReject.session();
        final int sequenceNumber = throttleReject.sequenceNumber();
        final int libraryId = throttleReject.libraryId();

        return onThrottle(position, sessionId, sequenceNumber, libraryId);
    }

    private boolean onThrottleNotification(final long position)
    {
        final ThrottleNotificationDecoder throttleNotification = this.throttleNotification;
        final long sessionId = throttleNotification.session();
        final int sequenceNumber = throttleNotification.refSeqNum();
        final int libraryId = throttleNotification.libraryId();

        return onThrottle(position, sessionId, sequenceNumber, libraryId);
    }

    private boolean onThrottle(
        final long position, final long sessionId, final int msgSequenceNumber, final int libraryId)
    {
        if (sessionOwnershipTracker.messageFromWrongLibrary(sessionId, libraryId))
        {
            return false;
        }

        int sequenceNumber = checkRedactPosition(position, sessionId);
        if (sequenceNumber == NO_SEQUENCE_NUMBER)
        {
            sequenceNumber = msgSequenceNumber;
        }
        saveRecord(
            sequenceNumber,
            sessionId,
            position,
            NO_REQUIRED_POSITION,
            false);

        return true;
    }

    private void onRedactSequenceUpdate()
    {
        final RedactSequenceUpdateDecoder redactSequenceUpdate = this.redactSequenceUpdate;

        final int newSequenceNumber = redactSequenceUpdate.correctSequenceNumber();
        final long sessionId = redactSequenceUpdate.session();

        onRedactSequenceUpdate(newSequenceNumber, sessionId, redactSequenceUpdate.position());
    }

    private void onRedactSequenceUpdate(final int newSequenceNumber, final long sessionId, final long position)
    {
        fixPSequenceIndexer.onRedactSequenceUpdate(sessionId, newSequenceNumber);

        saveRecord(
            newSequenceNumber,
            sessionId,
            position,
            position,
            false);
    }

    // return true if updated index
    private boolean onFixMessage(
        final DirectBuffer buffer,
        final int start,
        final int actingBlockLength,
        final int version,
        final long aeronSessionId,
        final long messagePosition)
    {
        int offset = start;

        messageFrame.wrap(buffer, offset, actingBlockLength, version);

        if (messageFrame.status() != MessageStatus.OK)
        {
            return false;
        }

        offset += actingBlockLength;

        final int metaDataOffset;
        final int metaDataLength;
        if (version >= metaDataSinceVersion())
        {
            metaDataLength = messageFrame.metaDataLength();
            metaDataOffset = messageFrame.metaDataUpdateOffset();
            resizeMetaDataBuffer(metaDataOffset + metaDataLength);
            messageFrame.getMetaData(metaDataWriteBuffer, metaDataOffset, metaDataLength);

            offset += FixMessageDecoder.metaDataHeaderLength() + metaDataLength;
        }
        else
        {
            metaDataLength = 0;
            metaDataOffset = 0;
        }

        final long sessionId = messageFrame.session();
        final int redactSequenceNumber = checkRedactPosition(messagePosition, sessionId);

        final int libraryId = messageFrame.libraryId();

        if (sessionOwnershipTracker.messageFromWrongLibrary(sessionId, libraryId))
        {
            return false;
        }

        offset += FixMessageDecoder.bodyHeaderLength();

        final int msgSeqNum;
        if (redactSequenceNumber == NO_SEQUENCE_NUMBER)
        {
            msgSeqNum = sequenceNumberExtractor.extractCached(
                buffer, offset, messageFrame.bodyLength(), aeronSessionId, messagePosition);
        }
        else
        {
            msgSeqNum = redactSequenceNumber;
        }

        if (msgSeqNum != NO_SEQUENCE_NUMBER)
        {
            final int position = saveRecord(msgSeqNum, sessionId, messagePosition, NO_REQUIRED_POSITION, false);
            if (metaDataLength > 0 && position > 0)
            {
                writeMetaDataToFile(position, metaDataWriteBuffer, metaDataOffset, metaDataLength);
            }
        }
        return true;
    }

    private int checkRedactPosition(final long messagePosition, final long sessionId)
    {
        final Long2LongHashMap redactPositionToSeqNum = sessionIdToRedactPositions.get(sessionId);
        if (redactPositionToSeqNum != null)
        {
            final int newSequenceNumber = (int)redactPositionToSeqNum.remove(messagePosition);
            if (newSequenceNumber != NO_SEQUENCE_NUMBER)
            {
                if (redactPositionToSeqNum.isEmpty())
                {
                    sessionIdToRedactPositions.remove(sessionId);
                }
                return newSequenceNumber;
            }
        }
        return NO_SEQUENCE_NUMBER;
    }

    private void resizeMetaDataBuffer(final int metaDataLength)
    {
        if (metaDataWriteBuffer.length < metaDataLength)
        {
            metaDataWriteBuffer = new byte[metaDataLength];
        }
    }

    private void onWriteMetaData()
    {
        final int libraryId = writeMetaData.libraryId();
        final long sessionId = writeMetaData.session();
        final long correlationId = writeMetaData.correlationId();
        final int metaDataOffset = writeMetaData.metaDataOffset();

        if (framerContext == null || metaDataFile == null)
        {
            writeMetaDataResponse(libraryId, correlationId, MetaDataStatus.FILE_ERROR);

            return;
        }

        final int sequenceNumberIndexFilePosition = (int)recordOffsets.get(sessionId);
        if (sequenceNumberIndexFilePosition == MISSING_RECORD)
        {
            writeMetaDataResponse(libraryId, correlationId, MetaDataStatus.UNKNOWN_SESSION);

            return;
        }

        final int metaDataLength = writeMetaData.metaDataLength();
        resizeMetaDataBuffer(metaDataOffset + metaDataLength);
        writeMetaData.getMetaData(metaDataWriteBuffer, metaDataOffset, metaDataLength);

        final MetaDataStatus status = writeMetaDataToFile(
            sequenceNumberIndexFilePosition, metaDataWriteBuffer, metaDataOffset, metaDataLength);
        writeMetaDataResponse(libraryId, correlationId, status);
    }

    private MetaDataStatus writeMetaDataToFile(
        final int sequenceNumberIndexFilePosition,
        final byte[] metaDataValue,
        final int metaDataUpdateOffset,
        final int metaDataUpdateLength)
    {
        final int oldMetaDataPosition = getMetaData(sequenceNumberIndexFilePosition);
        try
        {
            if (oldMetaDataPosition == NO_META_DATA)
            {
                if (metaDataUpdateOffset != 0)
                {
                    return MetaDataStatus.INVALID_OFFSET;
                }

                allocateMetaDataSlot(sequenceNumberIndexFilePosition, metaDataValue, metaDataUpdateLength);
            }
            else
            {
                metaDataFile.seek(oldMetaDataPosition);
                final int oldMetaDataLength = metaDataFile.readInt();
                final int newMetaDataMinLength = metaDataUpdateOffset + metaDataUpdateLength;
                // Is there space to replace?
                if (newMetaDataMinLength <= oldMetaDataLength)
                {
                    metaDataFile.seek(oldMetaDataPosition + SIZE_OF_META_DATA_LENGTH + metaDataUpdateOffset);
                    metaDataFile.write(metaDataValue, metaDataUpdateOffset, metaDataUpdateLength);
                }
                else
                {
                    // Pickup the old prefix that will be copied if it's an update
                    if (metaDataUpdateOffset > 0)
                    {
                        // Already at the old metadata position
                        metaDataFile.read(metaDataValue, 0, metaDataUpdateOffset);
                    }

                    allocateMetaDataSlot(sequenceNumberIndexFilePosition, metaDataValue, newMetaDataMinLength);
                }
            }

            return MetaDataStatus.OK;
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);

            return MetaDataStatus.FILE_ERROR;
        }
    }

    private void allocateMetaDataSlot(
        final int sequenceNumberIndexFilePosition,
        final byte[] metaDataValue,
        final int metaDataLength) throws IOException
    {
        final int metaDataFileInitialLength = (int)metaDataFile.length();
        updateMetaDataFile(metaDataFileInitialLength, metaDataValue, metaDataLength);
        putMetaDataField(sequenceNumberIndexFilePosition, metaDataFileInitialLength);
        hasSavedRecordSinceFileUpdate = true;
    }

    private void updateMetaDataFile(
        final int position, final byte[] metaDataValue, final int metaDataLength) throws IOException
    {
        metaDataFile.seek(position);
        metaDataFile.writeInt(metaDataValue.length);
        metaDataFile.write(metaDataValue, 0, metaDataLength);
    }

    private void writeMetaDataResponse(final int libraryId, final long correlationId, final MetaDataStatus status)
    {
        final WriteMetaDataResponse response = new WriteMetaDataResponse(libraryId, correlationId, status);
        if (!sendResponse(response))
        {
            responsesToResend.add(response);
        }
    }

    public int doWork()
    {
        int work = positionWriter != null ? positionWriter.checkRecordings() : 0;

        if (hasSavedRecordSinceFileUpdate)
        {
            final long requiredUpdateTimeInMs = lastUpdatedFileTimeInMs + indexFileStateFlushTimeoutInMs;
            if (requiredUpdateTimeInMs < clock.time())
            {
                updateFile();
                work++;
            }
        }

        return work + CollectionUtil.removeIf(responsesToResend, sendResponseFunc);
    }

    private boolean sendResponse(final WriteMetaDataResponse response)
    {
        return framerContext.offer(response);
    }

    void resetSequenceNumber(final long session, final long endPosition)
    {
        saveRecord(RESET_SEQUENCE, session, endPosition, NO_REQUIRED_POSITION, false);
    }

    void resetSequenceNumbers()
    {
        inMemoryBuffer.setMemory(0, indexedPositionsOffset, (byte)0);
        initialiseBlankBuffer();
        recordOffsets.clear();
        resetMetaDataFile();
    }

    private void resetMetaDataFile()
    {
        if (metaDataLocation != null)
        {
            try
            {
                metaDataFile.seek(0);
                metaDataFile.setLength(META_DATA_FILE_HEADER_LENGTH);
                writeMetaDataFileHeader(metaDataFile);
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }
    }

    private void checkTermRoll(
        final int aeronSessionId,
        final DirectBuffer buffer,
        final int offset,
        final long endPosition,
        final int length)
    {
        final long termBufferLength = buffer.capacity();
        long nextRollPosition = nextRollPositions.get(aeronSessionId);
        if (nextRollPosition == UNINITIALISED)
        {
            final long startPosition = endPosition - align(length, FRAME_ALIGNMENT);
            nextRollPosition = startPosition + termBufferLength - offset;
            nextRollPositions.put(aeronSessionId, nextRollPosition);
        }
        else if (endPosition > nextRollPosition)
        {
            nextRollPosition += termBufferLength;
            nextRollPositions.put(aeronSessionId, nextRollPosition);
            updateFile();
        }
    }

    private void updateFile()
    {
        checksumFramer.updateChecksums();
        if (positionWriter != null)
        {
            positionWriter.updateChecksums();
        }
        saveFile();
        flipFiles();
        hasSavedRecordSinceFileUpdate = false;
        lastUpdatedFileTimeInMs = clock.time();
        fileUpdateCount++;
    }

    private void saveFile()
    {
        writableFile.buffer().putBytes(0, inMemoryBuffer, 0, fileCapacity);
        writableFile.force();
        syncMetaDataFile();
    }

    private void syncMetaDataFile()
    {
        if (metaDataFile != null)
        {
            try
            {
                metaDataFile.getFD().sync();
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }
    }

    private void flipFiles()
    {
        if (RUNNING_ON_WINDOWS)
        {
            writableFile.close();
            indexFile.close();
        }

        final boolean flipsFiles = rename(indexPath, passingPlacePath) &&
            rename(writablePath, indexPath) &&
            rename(passingPlacePath, writablePath);

        if (RUNNING_ON_WINDOWS)
        {
            // remapping flips the files here due to the rename
            writableFile.map();
            indexFile.map();
        }
        else if (flipsFiles)
        {
            final MappedFile file = this.writableFile;
            writableFile = indexFile;
            indexFile = file;
        }
    }

    private boolean rename(final Path src, final Path dest)
    {
        try
        {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
            return false;
        }
    }

    public Path passingPlace()
    {
        return passingPlacePath;
    }

    public boolean isOpen()
    {
        return writableFile.isOpen();
    }

    public void close()
    {
        try
        {
            if (isOpen() && hasSavedRecordSinceFileUpdate)
            {
                updateFile();
            }
        }
        finally
        {
            Exceptions.closeAll(indexFile, writableFile, reader, () ->
            {
                if (metaDataFile != null)
                {
                    try
                    {
                        metaDataFile.close();
                    }
                    catch (final IOException e)
                    {
                        errorHandler.onError(e);
                    }
                }
            });
        }
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
        if (positionWriter != null)
        {
            // Inefficient, but only run once on startup, so not a big deal.
            new IndexedPositionReader(positionWriter.buffer()).readLastPosition(consumer);
        }
    }

    private int saveRecord(
        final int newSequenceNumber,
        final long sessionId,
        final long messagePosition,
        final long requiredPosition,
        final boolean incrementRequired)
    {
        int position = (int)recordOffsets.get(sessionId);
        if (position == MISSING_RECORD)
        {
            position = SequenceNumberIndexDescriptor.HEADER_SIZE;
            while (true)
            {
                position = checksumFramer.claim(position, RECORD_SIZE);
                if (position == OUT_OF_SPACE)
                {
                    errorHandler.onError(new IllegalStateException(
                        "Sequence Number Index out of space, can't claim slot for " + sessionId));
                    return position;
                }

                lastKnownDecoder.wrap(inMemoryBuffer, position, RECORD_SIZE, SCHEMA_VERSION);
                if (lastKnownDecoder.sessionId() == 0)
                {
                    // Don't redact if there's nothing to redact
                    if (requiredPosition == NO_REQUIRED_POSITION)
                    {
                        createNewRecord(newSequenceNumber, sessionId, position, messagePosition);
                        hasSavedRecordSinceFileUpdate = true;
                    }
                    else
                    {
                        saveRedactForLater(newSequenceNumber, requiredPosition, sessionId);
                    }
                    return position;
                }
                else if (lastKnownDecoder.sessionId() == sessionId)
                {
                    recordOffsets.put(sessionId, position);
                    updateSequenceNumber(
                        newSequenceNumber, position, messagePosition, requiredPosition, incrementRequired, sessionId);
                    return position;
                }

                position += RECORD_SIZE;
            }
        }
        else
        {
            updateSequenceNumber(
                newSequenceNumber, position, messagePosition, requiredPosition, incrementRequired, sessionId);
            return position;
        }
    }

    private void updateSequenceNumber(
        final int newSequenceNumber,
        final int recordOffset,
        final long messagePosition,
        final long requiredPosition,
        final boolean incrementRequired,
        final long sessionId)
    {
        if (requiredPosition != NO_REQUIRED_POSITION)
        {
            final long oldMessagePosition = getMessagePosition(recordOffset);
            if (oldMessagePosition > requiredPosition)
            {
                // Redacted message has been processed but we've already processed a later FIX message, don't redact.
                return;
            }
            else if (oldMessagePosition < requiredPosition)
            {
                // We've not processed the message that needs redacting yet, so keep track that we need to
                // redact this message when it arrives.
                saveRedactForLater(newSequenceNumber, requiredPosition, sessionId);
                return;
            }
        }

        final int oldSequenceNumber = getSequenceNumber(recordOffset);
        // Increment required in the case of an iLink3 possRetrans
        if (incrementRequired)
        {
            if (newSequenceNumber > oldSequenceNumber)
            {
                putMessagePosition(recordOffset, messagePosition);
                putSequenceNumber(recordOffset, newSequenceNumber);
                hasSavedRecordSinceFileUpdate = true;
            }
        }
        else
        {
            putMessagePosition(recordOffset, messagePosition);
            putSequenceNumber(recordOffset, newSequenceNumber);
            // When sequence number resets then old metadata has expired
            if (oldSequenceNumber > newSequenceNumber)
            {
                final int oldMetaDataPosition = getMetaData(recordOffset);
                if (oldMetaDataPosition != NO_META_DATA)
                {
                    putMetaDataField(recordOffset, NO_META_DATA);
                    try
                    {
                        metaDataFile.seek(oldMetaDataPosition);
                        final int metaDataLength = metaDataFile.readInt();
                        updateMetaDataFile(oldMetaDataPosition, new byte[metaDataLength], metaDataLength);
                    }
                    catch (final IOException e)
                    {
                        errorHandler.onError(e);
                    }
                }
            }
            hasSavedRecordSinceFileUpdate = true;
        }
    }

    private void saveRedactForLater(final int newSequenceNumber, final long requiredPosition, final long sessionId)
    {
        final Long2LongHashMap redactPositionToSeqNum = sessionIdToRedactPositions.computeIfAbsent(
            sessionId, ignore -> new Long2LongHashMap(NO_SEQUENCE_NUMBER));
        redactPositionToSeqNum.put(requiredPosition, newSequenceNumber);
    }

    private void createNewRecord(
        final int sequenceNumber,
        final long sessionId,
        final int position, final long messagePosition)
    {
        recordOffsets.put(sessionId, position);
        lastKnownEncoder
            .wrap(inMemoryBuffer, position)
            .sessionId(sessionId)
            .messagePosition(messagePosition);
        putSequenceNumber(position, sequenceNumber);
        putMetaDataField(position, NO_META_DATA);
    }

    private void initialiseBuffer()
    {
        validateBufferSizes();
        final AtomicBuffer fileBuffer = indexFile.buffer();
        if (fileHasBeenInitialized(fileBuffer))
        {
            readFile(fileBuffer);
        }
        else if (Files.exists(passingPlacePath))
        {
            if (rename(passingPlacePath, indexPath))
            {
                // TODO: fsync parent directory
                indexFile.remap();
                initialiseBuffer();
            }
            else
            {
                errorHandler.onError(new IllegalStateException(String.format(
                    "Unable to recover index file from %s to %s due to rename failure",
                    passingPlacePath,
                    indexPath)));
            }
        }
        else
        {
            initialiseBlankBuffer();
        }
    }

    private void initialiseBlankBuffer()
    {
        LoggerUtil.initialiseBuffer(
            inMemoryBuffer,
            fileHeaderEncoder,
            fileHeaderDecoder,
            lastKnownEncoder.sbeSchemaId(),
            lastKnownEncoder.sbeTemplateId(),
            lastKnownEncoder.sbeSchemaVersion(),
            lastKnownEncoder.sbeBlockLength(),
            errorHandler);
    }

    private boolean fileHasBeenInitialized(final AtomicBuffer fileBuffer)
    {
        return fileBuffer.getShort(0) != 0 || fileBuffer.getInt(FIRST_CHECKSUM_LOCATION) != 0;
    }

    private void validateBufferSizes()
    {
        final int inMemoryCapacity = inMemoryBuffer.capacity();

        if (fileCapacity != inMemoryCapacity)
        {
            throw new IllegalStateException(String.format(
                "In memory buffer and disk file don't have the same size, disk: %d, memory: %d",
                fileCapacity,
                inMemoryCapacity
            ));
        }

        if (fileCapacity < SECTOR_SIZE)
        {
            throw new IllegalStateException(String.format(
                "Cannot create sequence number of size < 1 sector: %d",
                fileCapacity));
        }
    }

    private void readFile(final AtomicBuffer fileBuffer)
    {
        loadBuffer(fileBuffer);
        checksumFramer.validateCheckSums();
    }

    private void loadBuffer(final AtomicBuffer fileBuffer)
    {
        inMemoryBuffer.putBytes(0, fileBuffer, 0, fileCapacity);
    }

    private void putMessagePosition(
        final int recordOffset,
        final long value)
    {
        inMemoryBuffer.putLongOrdered(recordOffset + MESSAGE_POSITION_OFFSET, value);
    }

    private void putSequenceNumber(
        final int recordOffset,
        final int value)
    {
        inMemoryBuffer.putIntOrdered(recordOffset + SEQUENCE_NUMBER_OFFSET, value);
    }

    private int getSequenceNumber(final int recordOffset)
    {
        return inMemoryBuffer.getIntVolatile(recordOffset + SEQUENCE_NUMBER_OFFSET);
    }

    private long getMessagePosition(final int recordOffset)
    {
        return inMemoryBuffer.getLongVolatile(recordOffset + MESSAGE_POSITION_OFFSET);
    }

    private void putMetaDataField(
        final int recordOffset,
        final int value)
    {
        inMemoryBuffer.putIntOrdered(recordOffset + META_DATA_OFFSET, value);
    }

    private int getMetaData(
        final int recordOffset)
    {
        return inMemoryBuffer.getIntVolatile(recordOffset + META_DATA_OFFSET);
    }

    public void framerContext(final FramerContext framerContext)
    {
        this.framerContext = framerContext;
    }

    public SequenceNumberIndexReader reader()
    {
        return reader;
    }

    long fileUpdateCount()
    {
        return fileUpdateCount;
    }
}
