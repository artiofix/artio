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
package uk.co.real_logic.artio;

import org.agrona.collections.Long2LongHashMap;

public enum LogTag
{
    LIBRARY_CONNECT,
    /**
     * If you just want to see FIX messages going into and out of Artio. These lines are labelled "Received",
     * "Sent" and "Invalidated". "Invalidated" means that the message has failed the first of message validation
     * before it is even enqueued into Aeron as applied by the ReceiverEndPoint.
     */
    FIX_MESSAGE,
    /**
     * This logs direct reads and writes of messages from TCP sockets. These lines are labelled "Read" and
     * "Written".
     *
     * NB: TCP reads may read partial messages or multiple messages at the same time. This isn't a bug.
     */
    FIX_MESSAGE_TCP,
    /**
     * This logs flow of messages within Artio. These will are labelled "Enqueued" as they are written into Aeron
     * streams between a Library and Engine instance.
     */
    FIX_MESSAGE_FLOW,
    FIX_CONNECTION,
    FIX_TEST,
    GATEWAY_MESSAGE,
    /**
     * This logs the flow of messages when application heartbeat messages are sent.
     */
    APPLICATION_HEARTBEAT,

    /**
     * This logs the flow of messages when application heartbeat messages are attempted but not sent. This
     * could become quite spammy in the case that you have a lot of back-pressure
     */
    APPLICATION_HEARTBEAT_ATTEMPT,

    POSITION,
    CATCHUP,
    REPLAY,
    REPLAY_MESSAGE,
    REPLAY_ATTEMPT,
    INDEX,
    LIBRARY_MANAGEMENT,
    /**
     * Logs information related to the closing down of Artio.
     */
    CLOSE,

    /**
     * Logs information relating to the archive pruning operation.
     *
     * This is a low volume LogTag to enable.
     *
     * @see uk.co.real_logic.artio.engine.FixEngine#pruneArchive(Long2LongHashMap)
     */
    STATE_CLEANUP,

    /**
     * Logs information from the proxy protocol - see http://www.haproxy.org/download/1.8/doc/proxy-protocol.txt
     * for details.
     */
    PROXY,

    /**
     * Prints out the ILink3 session messages.
     *
     * Replaced by <code>FIXP_SESSION</code>. This will be removed in a future version of Artio, until then it
     * will continue to work as an alias.
     */
    @Deprecated
    ILINK_SESSION,

    /**
     * Prints out the FIXP session messages.
     */
    FIXP_SESSION,

    /**
     * Prints out the ILink3 business layer messages.
     *
     * Replaced by <code>FIXP_BUSINESS</code>. This will be removed in a future version of Artio, until then it
     * will continue to work as an alias.
     */
    @Deprecated
    ILINK_BUSINESS,

    /**
     * Prints out the FIXP business layer messages.
     */
    FIXP_BUSINESS,

    /**
     * Prints out debug logging when using the {@link uk.co.real_logic.artio.engine.logger.FixArchiveScanner} and
     * associated {@link uk.co.real_logic.artio.engine.logger.FixArchivePrinter}
     */
    ARCHIVE_SCAN,

    /**
     * Prints out information associated with debugging reproduction runs. See
     * {@link uk.co.real_logic.artio.engine.EngineConfiguration#reproduceInbound(long, long)}.
     */
    REPRODUCTION,

    /**
     * Used by tests that perform reproduction.
     */
    REPRODUCTION_TEST,

    /**
     * Specifically for logging the cause of slow consumer disconnects.
     */
    SLOW_CONSUMER;

    private final char[] logStr;

    LogTag()
    {
        logStr = ("[" + name() + "]").toCharArray();
    }

    public char[] logStr()
    {
        return logStr;
    }
}
