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
package uk.co.real_logic.artio.session;

import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;

/**
 * A writer for a session that sends messages into the system.
 *
 * This can be used to integrate Artio into a clustering system by providing a way to write messages into the FIX log
 * that have been acknowledged by a cluster. In this way a passive or follower FIX Gateway can be kept up to date with
 * the messages from an active or leader Gateway.
 */
public class SessionWriter
{
    private final int libraryId;
    private final long sessionId;
    private final MutableAsciiBuffer asciiBuffer;
    private final GatewayPublication publication;

    private long connectionId;
    private int sequenceIndex;
    private InternalSession session;
    private boolean closed = false;

    public SessionWriter(
        final int libraryId,
        final long sessionId,
        final long connectionId,
        final MutableAsciiBuffer asciiBuffer,
        final GatewayPublication publication,
        final int sequenceIndex)
    {
        this.libraryId = libraryId;
        this.sessionId = sessionId;
        this.connectionId = connectionId;
        this.asciiBuffer = asciiBuffer;
        this.publication = publication;
        this.sequenceIndex = sequenceIndex;
    }

    // ---------- PUBLIC API ----------

    /**
     * Sets the sequence index to be used by this SessionWriter
     *
     * @param sequenceIndex the sequence index to be used by this SessionWriter
     * @throws IllegalStateException if the owning FixLibrary object has been closed.
     */
    public void sequenceIndex(final int sequenceIndex)
    {
        checkState();

        this.sequenceIndex = sequenceIndex;

        final Session session = this.session;
        if (session != null)
        {
            session.sequenceIndex(sequenceIndex);
        }
    }

    /**
     * Gets the current sequence index.
     *
     * @return the current sequence index
     */
    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    /**
     * Sets the current connection id. This can be obtained from {@link Session#connectionId()}.
     *
     * @param connectionId the current connection id.
     * @return this
     */
    public SessionWriter connectionId(final long connectionId)
    {
        this.connectionId = connectionId;
        return this;
    }

    /**
     * Gets the current connection id.
     *
     * @return the current connection id.
     */
    public long connectionId()
    {
        return connectionId;
    }

    /**
     * Send a message on this session.
     *
     * @param encoder the encoder of the message to be sent
     * @param seqNum the sequence number of the sent message
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws IndexOutOfBoundsException if the encoded message is too large, if this happens consider
     *                                   increasing {@link CommonConfiguration#sessionBufferSize(int)}
     * @throws IllegalStateException if the owning FixLibrary object has been closed.
     */
    public long send(final Encoder encoder, final int seqNum)
    {
        checkState();

        final long result = encoder.encode(asciiBuffer, 0);
        final int length = Encoder.length(result);
        final int offset = Encoder.offset(result);

        return send(asciiBuffer, offset, length, seqNum, encoder.messageType());
    }

    /**
     * Send a message on this session.
     *
     * @param messageBuffer the buffer with the FIX message in to send
     * @param offset the offset within the messageBuffer where the message starts
     * @param length the length of the message within the messageBuffer
     * @param seqNum the sequence number of the sent message
     * @param messageType the long encoded message type.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws IllegalStateException if the owning FixLibrary object has been closed.
     */
    public long send(
        final DirectBuffer messageBuffer, final int offset, final int length, final int seqNum, final long messageType)
    {
        checkState();

        final long position = publication.saveMessage(
            messageBuffer, offset, length, libraryId, messageType, sessionId, sequenceIndex, connectionId, OK, seqNum);

        if (position > 0)
        {
            DebugLogger.logFixMessage(FIX_MESSAGE, messageType, "Sent ", messageBuffer, offset, length);

            final InternalSession session = this.session;
            if (session != null)
            {
                session.lastSentMsgSeqNum(seqNum);

                if (messageType == SessionConstants.LOGOUT_MESSAGE_TYPE)
                {
                    session.onSessionWriterLogout();
                }
            }
        }

        return position;
    }

    /**
     * Request the TCP disconnect of a session.
     *
     * @param reason the reason to log for the disconnect.
     * @return the position in the stream that corresponds to the end of this message or a negative
     *         number indicating an error status.
     * @throws IllegalStateException if the owning FixLibrary object has been closed.
     */
    public long requestDisconnect(final DisconnectReason reason)
    {
        checkState();

        return publication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

    /**
     * Request replying to a resend request.
     * This should only be used with the parameters from
     * {@link SessionProxy#sendValidResendRequest(long, long, long, long, int, long, long, DirectBuffer, int, int)}.
     *
     * @param sessionId of the session.
     * @param connectionId of the session.
     * @param beginSequenceNumber of the resend request range.
     * @param endSequenceNumber of the resend request range.
     * @param sequenceIndex of the resend request range.
     * @param correlationId generated by the {@link Session} for this specific replay request.
     * @param overriddenBeginSequenceNumber of the resend request range.
     * @param bodyBuffer of the counterparty's resend request.
     * @param bodyOffset of the counterparty's resend request.
     * @param bodyLength of the counterparty's resend request.
     * @return the position in the stream that corresponds to the end of this message or a negative
     *         number indicating an error status.
     * @throws IllegalStateException if the owning FixLibrary object has been closed.
     */
    public long sendValidResendRequest(
        final long sessionId,
        final long connectionId,
        final long beginSequenceNumber,
        final long endSequenceNumber,
        final int sequenceIndex,
        final long correlationId,
        final long overriddenBeginSequenceNumber,
        final DirectBuffer bodyBuffer,
        final int bodyOffset,
        final int bodyLength)
    {
        checkState();

        return publication.saveValidResendRequest(
            sessionId,
            connectionId,
            beginSequenceNumber,
            endSequenceNumber,
            sequenceIndex,
            correlationId,
            overriddenBeginSequenceNumber,
            bodyBuffer,
            bodyOffset,
            bodyLength);
    }

    /**
     * Gets the id of the session that this is a writer for.
     *
     * @return the id of the session that this is a writer for.
     */
    public long id()
    {
        return sessionId;
    }

    // ---------- END OF PUBLIC API ----------

    // ---------- Internal API ----------

    void linkTo(final InternalSession session)
    {
        this.session = session;
        this.connectionId = session.connectionId();
    }

    void onDisconnect()
    {
        connectionId = NO_CONNECTION_ID;
    }

    void checkState()
    {
        if (closed)
        {
            throw new IllegalStateException("Library has been closed");
        }
    }

    /**
     * Session Writer objects closed when owning library is closed, no need for API users to close the object.
     */
    void close()
    {
        closed = true;
    }
}
