/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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


import org.agrona.AsciiSequenceView;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.AbstractDebugAppender.ThreadLocalAppender;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.CharFormatter;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import static uk.co.real_logic.artio.CommonConfiguration.*;

/**
 * A logger purely for debug data. Not optimised for high performance logging, but all logging calls must be removable
 * by the optimiser.
 */
public final class DebugLogger
{
    private static final AbstractDebugAppender APPENDER;
    private static final ThreadLocal<ThreadLocalLogger> THREAD_LOCAL = ThreadLocal.withInitial(ThreadLocalLogger::new);

    public static final boolean IS_REPLAY_LOG_TAG_ENABLED = isEnabled(LogTag.REPLAY);
    public static final boolean IS_REPLAY_ATTEMPT_ENABLED = isEnabled(LogTag.REPLAY_ATTEMPT);

    static
    {
        final ServiceLoader<AbstractDebugAppender> loader = ServiceLoader.load(AbstractDebugAppender.class);
        final Iterator<AbstractDebugAppender> it = loader.iterator();
        if (it.hasNext())
        {
            APPENDER = it.next();
            if (DEBUG_FILE != null)
            {
                System.err.println("Warning: -D" + DEBUG_FILE_PROPERTY + " has been set, despite a custom " +
                    "AbstractDebugAppender (" + APPENDER.getClass() + ") being configured via the service loader. " +
                    "The file property will be ignored by Artio and your customer appender used instead.");
            }
        }
        else
        {
            APPENDER = new PrintingDebugAppender();
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final int value,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(value);
            THREAD_LOCAL.get().log(tag, formatter, buffer, offset, length);
        }
    }

    public static void logFixMessage(
        final LogTag tag,
        final long messageType,
        final CharFormatter formatter,
        final int value,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag) && isEnabled(messageType))
        {
            formatter.clear().with(value);
            THREAD_LOCAL.get().log(tag, formatter, buffer, offset, length);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final String second,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second);
            THREAD_LOCAL.get().log(tag, formatter, buffer, offset, length);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, formatter, buffer, offset, length);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final RedactSequenceUpdateEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReplayCompleteEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final SeqIndexSyncEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ThrottleConfigurationEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ThrottleConfigurationReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ManageSessionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final DisconnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ConnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ResetSessionIdsEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ResetSequenceNumberEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ResetLibrarySequenceNumberEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final RequestDisconnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final MidConnectionDisconnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final InitiateConnectionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ErrorEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ApplicationHeartbeatEncoder encoder,
        final int streamId)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder, streamId);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final LibraryConnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReleaseSessionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReleaseSessionReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final RequestSessionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final RequestSessionReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final LibraryTimeoutEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ControlNotificationEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final SlowStatusNotificationEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final FollowerSessionRequestEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final FollowerSessionReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final EndOfDayEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final WriteMetaDataEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final WriteMetaDataReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReadMetaDataEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReadMetaDataReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReplayMessagesEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final ReplayMessagesReplyEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag, final InitiateILinkConnectionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag, final ILinkConnectEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag, final LibraryExtendPositionEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag, final ValidResendRequestEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeDecoder(
        final LogTag tag, final String prefix, final Consumer<StringBuilder> appendTo)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeDecoder(tag, prefix, appendTo);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final StartReplayEncoder encoder)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final long messageType,
        final ThrottleNotificationEncoder encoder)
    {
        if (isEnabled(tag) && isEnabled(messageType))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void logSbeMessage(
        final LogTag tag,
        final long messageType,
        final ThrottleRejectEncoder encoder)
    {
        if (isEnabled(tag) && isEnabled(messageType))
        {
            THREAD_LOCAL.get().logSbeMessage(tag, encoder);
        }
    }

    public static void log(
        final LogTag tag,
        final String prefixString,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, prefixString, buffer, offset, length);
        }
    }

    public static void logFixMessage(
        final LogTag tag,
        final long messageType,
        final String prefixString,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag) && isEnabled(messageType))
        {
            THREAD_LOCAL.get().log(tag, prefixString, buffer, offset, length);
        }
    }

    public static void logBytes(
        final LogTag tag,
        final String prefixString,
        final ByteBuffer buffer,
        final int offset,
        final int length)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().logBytes(tag, prefixString, buffer, offset, length);
        }
    }

    public static void log(
        final LogTag tag,
        final String prefixString,
        final ByteBuffer byteBuffer,
        final int length)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, prefixString, byteBuffer, length);
        }
    }

    public static void log(
        final LogTag tag,
        final String message)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, message);
        }
    }

    public static void log(
        final LogTag tag,
        final String prefixString,
        final String suffixString)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, prefixString, suffixString);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final String second)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final long second)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final long second,
        final long third)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second).with(third);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final String first,
        final long second,
        final long third)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second).with(third);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final long second,
        final String third)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second).with(third);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final long first,
        final long second,
        final long third,
        final long fourth)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second).with(third).with(fourth);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    public static void log(
        final LogTag tag,
        final CharFormatter formatter,
        final String first,
        final long second,
        final long third,
        final long fourth)
    {
        if (isEnabled(tag))
        {
            formatter.clear().with(first).with(second).with(third).with(fourth);
            THREAD_LOCAL.get().log(tag, formatter);
        }
    }

    // Used by fix-integration project
    public static void log(
        final LogTag tag,
        final String formatString,
        final Object first,
        final Object second)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, String.format(formatString, first, second));
        }
    }

    public static void log(
        final LogTag tag,
        final String formatString,
        final Object[] first)
    {
        if (isEnabled(tag))
        {
            THREAD_LOCAL.get().log(tag, String.format(formatString, Arrays.toString(first)));
        }
    }

    static String threadName()
    {
        return Thread.currentThread().getName();
    }

    private static void substituteSeparator(final byte[] data)
    {
        if (needsSeparatorSubstitution())
        {
            final int size = data.length;
            for (int i = 0; i < size; i++)
            {
                if (data[i] == DEFAULT_DEBUG_LOGGING_SEPARATOR)
                {
                    data[i] = DEBUG_LOGGING_SEPARATOR;
                }
            }
        }
    }

    private static boolean needsSeparatorSubstitution()
    {
        return DEBUG_LOGGING_SEPARATOR != DEFAULT_DEBUG_LOGGING_SEPARATOR;
    }

    public static boolean isEnabled(final LogTag tag)
    {
        return DEBUG_PRINT_MESSAGES && DEBUG_TAGS.contains(tag);
    }

    private static boolean isEnabled(final long messageType)
    {
        return DEBUG_PRINT_MESSAGE_TYPES == null || DEBUG_PRINT_MESSAGE_TYPES.contains(messageType);
    }

    public static boolean inboundFixMessage(final int templateId)
    {
        return FixMessageDecoder.TEMPLATE_ID == templateId || ThrottleNotificationDecoder.TEMPLATE_ID == templateId;
    }

    public static boolean outboundFixMessage(final int templateId)
    {
        return FixMessageDecoder.TEMPLATE_ID == templateId || ThrottleRejectDecoder.TEMPLATE_ID == templateId;
    }

    static class ThreadLocalLogger
    {
        // Library -> Engine
        private final InitiateConnectionDecoder initiateConnection = new InitiateConnectionDecoder();
        private final RequestDisconnectDecoder requestDisconnect = new RequestDisconnectDecoder();
        private final MidConnectionDisconnectDecoder midConnectionDisconnect = new MidConnectionDisconnectDecoder();
        private final LibraryConnectDecoder libraryConnect = new LibraryConnectDecoder();
        private final ReleaseSessionDecoder releaseSession = new ReleaseSessionDecoder();
        private final RequestSessionDecoder requestSession = new RequestSessionDecoder();
        private final FollowerSessionRequestDecoder followerSessionRequest = new FollowerSessionRequestDecoder();
        private final WriteMetaDataDecoder writeMetaData = new WriteMetaDataDecoder();
        private final ReadMetaDataDecoder readMetaData = new ReadMetaDataDecoder();
        private final ReplayMessagesDecoder replayMessages = new ReplayMessagesDecoder();
        private final ConnectDecoder connect = new ConnectDecoder();
        private final ResetSessionIdsDecoder resetSessionIds = new ResetSessionIdsDecoder();
        private final LibraryTimeoutDecoder libraryTimeout = new LibraryTimeoutDecoder();
        private final InitiateILinkConnectionDecoder initiateILinkConnection =
            new InitiateILinkConnectionDecoder();
        private final ThrottleRejectDecoder throttleReject = new ThrottleRejectDecoder();
        private final ThrottleConfigurationDecoder throttleConfiguration = new ThrottleConfigurationDecoder();
        private final ThrottleConfigurationReplyDecoder throttleConfigurationReply =
            new ThrottleConfigurationReplyDecoder();
        private final SeqIndexSyncDecoder seqIndexSync = new SeqIndexSyncDecoder();

        // Engine -> Library
        private final ErrorDecoder error = new ErrorDecoder();
        private final ReleaseSessionReplyDecoder releaseSessionReply = new ReleaseSessionReplyDecoder();
        private final RequestSessionReplyDecoder requestSessionReply = new RequestSessionReplyDecoder();
        private final WriteMetaDataReplyDecoder writeMetaDataReply = new WriteMetaDataReplyDecoder();
        private final ReadMetaDataReplyDecoder readMetaDataReply = new ReadMetaDataReplyDecoder();
        private final ControlNotificationDecoder controlNotification = new ControlNotificationDecoder();
        private final SlowStatusNotificationDecoder slowStatusNotification = new SlowStatusNotificationDecoder();
        private final ResetLibrarySequenceNumberDecoder resetLibrarySequenceNumber =
            new ResetLibrarySequenceNumberDecoder();
        private final ResetSequenceNumberDecoder resetSequenceNumber =
            new ResetSequenceNumberDecoder();
        private final ManageSessionDecoder manageSession = new ManageSessionDecoder();
        private final FollowerSessionReplyDecoder followerSessionReply = new FollowerSessionReplyDecoder();
        private final EndOfDayDecoder endOfDay = new EndOfDayDecoder();
        private final ReplayMessagesReplyDecoder replayMessagesReply = new ReplayMessagesReplyDecoder();
        private final ValidResendRequestDecoder validResendRequest = new ValidResendRequestDecoder();
        private final LibraryExtendPositionDecoder libraryExtendPosition = new LibraryExtendPositionDecoder();
        private final ILinkConnectDecoder iLinkConnect = new ILinkConnectDecoder();
        private final ThrottleNotificationDecoder throttleNotification = new ThrottleNotificationDecoder();

        // Common
        private final ApplicationHeartbeatDecoder applicationHeartbeat = new ApplicationHeartbeatDecoder();
        private final DisconnectDecoder disconnect = new DisconnectDecoder();
        private final RedactSequenceUpdateDecoder redactSequenceUpdate = new RedactSequenceUpdateDecoder();

        // Replayer
        private final StartReplayDecoder startReplay = new StartReplayDecoder();
        private final ReplayCompleteDecoder replayComplete = new ReplayCompleteDecoder();

        private final StringBuilder builder = new StringBuilder();

        private byte[] bytes = new byte[0];
        private final AsciiSequenceView asciiView = new AsciiSequenceView();
        private final UnsafeBuffer buffer = new UnsafeBuffer(bytes);
        private final ThreadLocalAppender appender;

        final boolean isThreadEnabled;

        ThreadLocalLogger()
        {
            final String threadName = threadName();
            isThreadEnabled = DEBUG_PRINT_THREAD == null || DEBUG_PRINT_THREAD.equals(threadName);
            appender = APPENDER.makeLocalAppender();
        }

        public void logSbeMessage(final LogTag tag, final StartReplayEncoder encoder)
        {
            appendStart();
            startReplay.wrap(
                encoder.buffer(),
                encoder.offset(),
                RedactSequenceUpdateEncoder.BLOCK_LENGTH,
                RedactSequenceUpdateEncoder.SCHEMA_VERSION);
            startReplay.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ReplayCompleteEncoder encoder)
        {
            appendStart();
            replayComplete.wrap(
                encoder.buffer(),
                encoder.offset(),
                RedactSequenceUpdateEncoder.BLOCK_LENGTH,
                RedactSequenceUpdateEncoder.SCHEMA_VERSION);
            replayComplete.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final RedactSequenceUpdateEncoder encoder)
        {
            appendStart();
            redactSequenceUpdate.wrap(
                encoder.buffer(),
                encoder.offset(),
                RedactSequenceUpdateEncoder.BLOCK_LENGTH,
                RedactSequenceUpdateEncoder.SCHEMA_VERSION);
            redactSequenceUpdate.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final SeqIndexSyncEncoder encoder)
        {
            appendStart();
            seqIndexSync.wrap(
                encoder.buffer(),
                encoder.offset(),
                SeqIndexSyncEncoder.BLOCK_LENGTH,
                SeqIndexSyncEncoder.SCHEMA_VERSION);
            seqIndexSync.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ThrottleConfigurationEncoder encoder)
        {
            appendStart();
            throttleConfiguration.wrap(
                encoder.buffer(),
                encoder.offset(),
                ThrottleConfigurationEncoder.BLOCK_LENGTH,
                ThrottleConfigurationEncoder.SCHEMA_VERSION);
            throttleConfiguration.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ThrottleConfigurationReplyEncoder encoder)
        {
            appendStart();
            throttleConfigurationReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                ThrottleConfigurationReplyEncoder.BLOCK_LENGTH,
                ThrottleConfigurationReplyEncoder.SCHEMA_VERSION);
            throttleConfigurationReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ManageSessionEncoder encoder)
        {
            appendStart();
            manageSession.wrap(
                encoder.buffer(),
                encoder.offset(),
                ManageSessionEncoder.BLOCK_LENGTH,
                ManageSessionEncoder.SCHEMA_VERSION);
            manageSession.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final DisconnectEncoder encoder)
        {
            appendStart();
            disconnect.wrap(
                encoder.buffer(),
                encoder.offset(),
                DisconnectEncoder.BLOCK_LENGTH,
                DisconnectEncoder.SCHEMA_VERSION);
            disconnect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ConnectEncoder encoder)
        {
            appendStart();
            connect.wrap(
                encoder.buffer(),
                encoder.offset(),
                ConnectEncoder.BLOCK_LENGTH,
                ConnectEncoder.SCHEMA_VERSION);
            connect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ResetSessionIdsEncoder encoder)
        {
            appendStart();
            resetSessionIds.wrap(
                encoder.buffer(),
                encoder.offset(),
                ResetSessionIdsEncoder.BLOCK_LENGTH,
                ResetSessionIdsEncoder.SCHEMA_VERSION);
            resetSessionIds.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ResetSequenceNumberEncoder encoder)
        {
            appendStart();
            resetSequenceNumber.wrap(
                encoder.buffer(),
                encoder.offset(),
                ResetSequenceNumberEncoder.BLOCK_LENGTH,
                ResetSequenceNumberEncoder.SCHEMA_VERSION);
            resetSequenceNumber.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ResetLibrarySequenceNumberEncoder encoder)
        {
            appendStart();
            resetLibrarySequenceNumber.wrap(
                encoder.buffer(),
                encoder.offset(),
                ResetLibrarySequenceNumberEncoder.BLOCK_LENGTH,
                ResetLibrarySequenceNumberEncoder.SCHEMA_VERSION);
            resetLibrarySequenceNumber.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final RequestDisconnectEncoder encoder)
        {
            appendStart();
            requestDisconnect.wrap(
                encoder.buffer(),
                encoder.offset(),
                RequestDisconnectEncoder.BLOCK_LENGTH,
                RequestDisconnectEncoder.SCHEMA_VERSION);
            requestDisconnect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final MidConnectionDisconnectEncoder encoder)
        {
            appendStart();
            midConnectionDisconnect.wrap(
                encoder.buffer(),
                encoder.offset(),
                MidConnectionDisconnectEncoder.BLOCK_LENGTH,
                MidConnectionDisconnectEncoder.SCHEMA_VERSION);
            midConnectionDisconnect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final InitiateConnectionEncoder encoder)
        {
            appendStart();
            initiateConnection.wrap(
                encoder.buffer(),
                encoder.offset(),
                InitiateConnectionEncoder.BLOCK_LENGTH,
                InitiateConnectionEncoder.SCHEMA_VERSION);
            initiateConnection.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ErrorEncoder encoder)
        {
            appendStart();
            error.wrap(
                encoder.buffer(),
                encoder.offset(),
                ErrorEncoder.BLOCK_LENGTH,
                ErrorEncoder.SCHEMA_VERSION);
            error.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ApplicationHeartbeatEncoder encoder,
            final int streamId)
        {
            final StringBuilder builder = this.builder;
            appendStart();
            builder.append("streamId=");
            builder.append(streamId);
            applicationHeartbeat.wrap(
                encoder.buffer(),
                encoder.offset(),
                ApplicationHeartbeatEncoder.BLOCK_LENGTH,
                ApplicationHeartbeatEncoder.SCHEMA_VERSION);
            applicationHeartbeat.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final LibraryConnectEncoder encoder)
        {
            appendStart();
            libraryConnect.wrap(
                encoder.buffer(),
                encoder.offset(),
                LibraryConnectEncoder.BLOCK_LENGTH,
                LibraryConnectEncoder.SCHEMA_VERSION);
            libraryConnect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReleaseSessionEncoder encoder)
        {
            appendStart();
            releaseSession.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReleaseSessionEncoder.BLOCK_LENGTH,
                ReleaseSessionEncoder.SCHEMA_VERSION);
            releaseSession.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReleaseSessionReplyEncoder encoder)
        {
            appendStart();
            releaseSessionReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReleaseSessionReplyEncoder.BLOCK_LENGTH,
                ReleaseSessionReplyEncoder.SCHEMA_VERSION);
            releaseSessionReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final RequestSessionEncoder encoder)
        {
            appendStart();
            requestSession.wrap(
                encoder.buffer(),
                encoder.offset(),
                RequestSessionEncoder.BLOCK_LENGTH,
                RequestSessionEncoder.SCHEMA_VERSION);
            requestSession.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final RequestSessionReplyEncoder encoder)
        {
            appendStart();
            requestSessionReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                RequestSessionReplyEncoder.BLOCK_LENGTH,
                RequestSessionReplyEncoder.SCHEMA_VERSION);
            requestSessionReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final LibraryTimeoutEncoder encoder)
        {
            appendStart();
            libraryTimeout.wrap(
                encoder.buffer(),
                encoder.offset(),
                LibraryTimeoutEncoder.BLOCK_LENGTH,
                LibraryTimeoutEncoder.SCHEMA_VERSION);
            libraryTimeout.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ControlNotificationEncoder encoder)
        {
            appendStart();
            controlNotification.wrap(
                encoder.buffer(),
                encoder.offset(),
                ControlNotificationEncoder.BLOCK_LENGTH,
                ControlNotificationEncoder.SCHEMA_VERSION);
            controlNotification.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final SlowStatusNotificationEncoder encoder)
        {
            appendStart();
            slowStatusNotification.wrap(
                encoder.buffer(),
                encoder.offset(),
                SlowStatusNotificationEncoder.BLOCK_LENGTH,
                SlowStatusNotificationEncoder.SCHEMA_VERSION);
            slowStatusNotification.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final FollowerSessionRequestEncoder encoder)
        {
            appendStart();
            followerSessionRequest.wrap(
                encoder.buffer(),
                encoder.offset(),
                FollowerSessionRequestEncoder.BLOCK_LENGTH,
                FollowerSessionRequestEncoder.SCHEMA_VERSION);
            followerSessionRequest.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final FollowerSessionReplyEncoder encoder)
        {
            appendStart();
            followerSessionReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                FollowerSessionReplyEncoder.BLOCK_LENGTH,
                FollowerSessionReplyEncoder.SCHEMA_VERSION);
            followerSessionReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final EndOfDayEncoder encoder)
        {
            appendStart();
            endOfDay.wrap(
                encoder.buffer(),
                encoder.offset(),
                EndOfDayEncoder.BLOCK_LENGTH,
                EndOfDayEncoder.SCHEMA_VERSION);
            endOfDay.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final WriteMetaDataEncoder encoder)
        {
            appendStart();
            writeMetaData.wrap(
                encoder.buffer(),
                encoder.offset(),
                WriteMetaDataEncoder.BLOCK_LENGTH,
                WriteMetaDataEncoder.SCHEMA_VERSION);
            writeMetaData.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final WriteMetaDataReplyEncoder encoder)
        {
            appendStart();
            writeMetaDataReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                WriteMetaDataReplyEncoder.BLOCK_LENGTH,
                WriteMetaDataReplyEncoder.SCHEMA_VERSION);
            writeMetaDataReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReadMetaDataEncoder encoder)
        {
            appendStart();
            readMetaData.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReadMetaDataEncoder.BLOCK_LENGTH,
                ReadMetaDataEncoder.SCHEMA_VERSION);
            readMetaData.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReadMetaDataReplyEncoder encoder)
        {
            appendStart();
            readMetaDataReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReadMetaDataReplyEncoder.BLOCK_LENGTH,
                ReadMetaDataReplyEncoder.SCHEMA_VERSION);
            readMetaDataReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReplayMessagesEncoder encoder)
        {
            appendStart();
            replayMessages.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReplayMessagesEncoder.BLOCK_LENGTH,
                ReplayMessagesEncoder.SCHEMA_VERSION);
            replayMessages.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ReplayMessagesReplyEncoder encoder)
        {
            appendStart();
            replayMessagesReply.wrap(
                encoder.buffer(),
                encoder.offset(),
                ReplayMessagesReplyEncoder.BLOCK_LENGTH,
                ReplayMessagesReplyEncoder.SCHEMA_VERSION);
            replayMessagesReply.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final ValidResendRequestEncoder encoder)
        {
            appendStart();
            validResendRequest.wrap(
                encoder.buffer(),
                encoder.offset(),
                ValidResendRequestEncoder.BLOCK_LENGTH,
                ValidResendRequestEncoder.SCHEMA_VERSION);
            validResendRequest.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(
            final LogTag tag,
            final LibraryExtendPositionEncoder encoder)
        {
            appendStart();
            libraryExtendPosition.wrap(
                encoder.buffer(),
                encoder.offset(),
                LibraryExtendPositionEncoder.BLOCK_LENGTH,
                LibraryExtendPositionEncoder.SCHEMA_VERSION);
            libraryExtendPosition.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final InitiateILinkConnectionEncoder encoder)
        {
            appendStart();
            initiateILinkConnection.wrap(
                encoder.buffer(),
                encoder.offset(),
                InitiateILinkConnectionEncoder.BLOCK_LENGTH,
                InitiateILinkConnectionEncoder.SCHEMA_VERSION);
            initiateILinkConnection.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ILinkConnectEncoder encoder)
        {
            appendStart();
            iLinkConnect.wrap(
                encoder.buffer(),
                encoder.offset(),
                ILinkConnectEncoder.BLOCK_LENGTH,
                ILinkConnectEncoder.SCHEMA_VERSION);
            iLinkConnect.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ThrottleRejectEncoder encoder)
        {
            appendStart();
            throttleReject.wrap(
                encoder.buffer(),
                encoder.offset(),
                ThrottleRejectEncoder.BLOCK_LENGTH,
                ThrottleRejectEncoder.SCHEMA_VERSION);
            throttleReject.appendTo(builder);
            finish(tag);
        }

        public void logSbeMessage(final LogTag tag, final ThrottleNotificationEncoder encoder)
        {
            appendStart();
            throttleNotification.wrap(
                encoder.buffer(),
                encoder.offset(),
                ThrottleNotificationEncoder.BLOCK_LENGTH,
                ThrottleNotificationEncoder.SCHEMA_VERSION);
            throttleNotification.appendTo(builder);
            finish(tag);
        }

        public void logSbeDecoder(final LogTag tag, final String prefix, final Consumer<StringBuilder> appendTo)
        {
            appendStart();
            builder.append(prefix);
            appendTo.accept(builder);
            finish(tag);
        }

        private void appendStart()
        {
            final StringBuilder builder = this.builder;
            builder.setLength(0);
        }

        public void log(final LogTag tag, final String prefixString, final ByteBuffer byteBuffer, final int length)
        {
            final byte[] data = getByteArray(length);
            byteBuffer.get(data, 0, length);
            substituteSeparator(data);

            appendStart();
            final StringBuilder builder = this.builder;
            builder.append(prefixString);
            final AsciiSequenceView asciiView = this.asciiView;
            asciiView.wrap(buffer, 0, length);
            builder.append(asciiView);
            finish(tag);
        }

        public void logBytes(
            final LogTag tag,
            final String prefixString,
            final ByteBuffer byteBuffer,
            final int offset,
            final int length)
        {
            appendStart();
            final StringBuilder builder = this.builder;
            builder.append(prefixString);

            if (length == 0)
            {
                builder.append("{}");
            }
            else
            {
                final int origLimit = byteBuffer.limit();
                ByteBufferUtil.limit(byteBuffer, offset + length);

                builder.append('{');
                for (int i = 0; i < length; i++)
                {
                    builder.append(byteBuffer.get(offset + i));
                    if (i == length - 1)
                    {
                        builder.append('}');
                    }
                    else
                    {
                        builder.append(", ");
                    }
                }
                byteBuffer.limit(origLimit);
            }
            finish(tag);
        }

        private byte[] getByteArray(final int length)
        {
            byte[] data = this.bytes;
            if (data.length < length)
            {
                this.bytes = data = new byte[length];
                buffer.wrap(data);
            }
            return data;
        }

        public void log(
            final LogTag tag, final String message)
        {
            appendStart();
            builder.append(message);
            finish(tag);
        }

        public void log(
            final LogTag tag, final String prefixString, final DirectBuffer buffer, final int offset, final int length)
        {
            appendStart();
            builder.append(prefixString);

            if (needsSeparatorSubstitution())
            {
                final byte[] data = getByteArray(length);
                buffer.getBytes(offset, data, 0, length);
                substituteSeparator(data);
                asciiView.wrap(this.buffer, 0, length);
            }
            else
            {
                asciiView.wrap(buffer, offset, length);
            }

            builder.append(asciiView);
            finish(tag);
        }

        public void log(
            final LogTag tag,
            final CharFormatter formatter,
            final DirectBuffer buffer,
            final int bufferOffset,
            final int bufferLength)
        {
            final byte[] data = getByteArray(bufferLength);
            buffer.getBytes(bufferOffset, data, 0, bufferLength);
            substituteSeparator(data);

            formatter.with(data, bufferLength);

            log(tag, formatter);
        }

        public void log(
            final LogTag tag,
            final String prefixString,
            final String suffixString)
        {
            appendStart();
            final StringBuilder builder = this.builder;
            builder.append(prefixString);
            builder.append(suffixString);
            finish(tag);
        }

        public void log(
            final LogTag tag,
            final CharFormatter formatter)
        {
            appendStart();
            formatter.appendTo(builder);
            finish(tag);
        }

        private void finish(final LogTag tag)
        {
            final StringBuilder builder = this.builder;
            builder.append(System.lineSeparator());
            appender.log(tag, builder);
        }
    }
}
