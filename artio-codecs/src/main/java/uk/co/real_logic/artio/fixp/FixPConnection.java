/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.fixp;

import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.messages.DisconnectReason;

/**
 * Represents a Connection of a Binary protocol based upon FIXP - for example iLink3 or Binary Entrypoint
 * This is a FIXP session protocol with SBE encoded binary messages. Unlike FIX it possible to have multiple connections
 * open with the same session id.
 *
 * NB: This is an experimental API and is subject to change or potentially removal.
 */
public interface FixPConnection
{
    long NOT_AWAITING_RETRANSMIT = -1L;

    /**
     * Defines the internal state of the Session, this can be accessed using
     * the {@link FixPConnection#state()} method.
     */
    enum State
    {
        /** Initiator TCP connection has been established, but the negotiate not sent.*/
        CONNECTED,
        /** Initiator has sent the Negotiate message sent but no reply received yet. */
        SENT_NEGOTIATE,
        /** Initiator has not sent the Negotiate message due to back-pressure in Artio, retrying attempt to send. */
        RETRY_NEGOTIATE,
        /** Initiator has received a Negotiate Reject message. */
        NEGOTIATE_REJECTED,
        /** Initiator has had a Negotiate accepted, Establish not sent */
        NEGOTIATED,
        /** Initiator has had a Negotiate accepted, Establish sent */
        SENT_ESTABLISH,
        /**
         * Negotiate accepted, The Establish message hasn't been sent due to back-pressure in Artio,
         * retrying attempt to send.
         */
        RETRY_ESTABLISH,
        /** Received an Establish Reject message. */
        ESTABLISH_REJECTED,

        /** Acceptor TCP connection has been established, but the negotiate not received.*/
        ACCEPTED,
        /** Acceptor has received a negotiate message and responded to it. */
        SENT_NEGOTIATE_RESPONSE,
        /** Acceptor has not sent the Negotiate response message due to back-pressure, retrying attempt to send. */
        RETRY_NEGOTIATE_RESPONSE,
        /** Acceptor has received a negotiate message and rejected it. */
        SENT_NEGOTIATE_REJECT,
        /** Acceptor has not sent the Negotiate reject message due to back-pressure, retrying attempt to send. */
        RETRY_NEGOTIATE_REJECT,
        /** Acceptor has not sent the Establish ack message due to back-pressure, retrying attempt to send. */
        RETRY_ESTABLISH_ACK,
        /** Acceptor has received an establish message and rejected it. */
        SENT_ESTABLISH_REJECT,
        /** Acceptor has not sent the Establish reject message due to back-pressure, retrying attempt to send. */
        RETRY_ESTABLISH_REJECT,
        /** Acceptor state, counter-party re-established previous connection with an establish message */
        NEGOTIATED_REESTABLISH,
        /** Establish accepted, messages can be exchanged */
        ESTABLISHED,
        /** An idempotent flow session is currently retransmitting messages in response to a NotApplied message. */
        RETRANSMITTING,
        /** We've sent a finished sending message but not received a finished receiving */
        SENT_FINISHED_SENDING,
        /** We've attempted to send a finished sending message but been back-pressured */
        RETRY_FINISHED_SENDING,
        /**
         * We've received a finished receiving from a counter-party in response to our finished sending but
         * haven't been sent a finished sending.
         */
        RECV_FINISHED_RECEIVING_ONLY,
        /** We've received a finished sending message and sent a reply */
        RECV_FINISHED_SENDING,
        /** We've sent our own finished sending message, having received a finished sending message */
        REPLIED_FINISHED_SENDING,
        /** We're trying to resend our own finished sending message, having received a finished sending message */
        RETRY_REPLY_FINISHED_SENDING,
        /**
         * keepAliveInterval has expired without receiving a message from the counter-party - we are waiting that long
         * again before terminating.
         */
        AWAITING_KEEPALIVE,
        /**
         * An initiating Terminate message hasn't been sent due to back-pressure in Artio, retrying attempt to send.
         */
        RESEND_TERMINATE,
        /**
         * An acknowledging Terminate message hasn't been sent due to back-pressure in Artio, retrying attempt to send.
         */
        RESEND_TERMINATE_ACK,
        /**
         * We are awaiting a reply to a Terminate message. If keepAliveTimeout expires without a reply the TCP
         * connection will be disconnected.
         */
        UNBINDING,
        /**
         * This session has sent a Terminate message in order to initiate a terminate, but has not received a reply
         * yet.
         */
        SENT_TERMINATE,
        /** The session has been disconnected at the TCP level. */
        UNBOUND
    }

    // -----------------------------------------------
    // Operations
    // -----------------------------------------------

    /**
     * Tries to send a business layer message with no variable length or group fields. This method claims a buffer
     * slot for the flyweight object to wrap. If this method returns successfully then the flyweight's fields can be
     * used. After the flyweight is filled in then the {@link #commit()} method should be used to commit the message.
     *
     * @param message the business layer message to send.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @see #tryClaim(MessageEncoderFlyweight, int)
     */
    long tryClaim(MessageEncoderFlyweight message);

    /**
     * Tries to send a business layer message with a variable length or group fields. This method claims a buffer
     * slot for the flyweight object to wrap. If this method returns successfully then the flyweight's fields can be
     * used. After the flyweight is filled in then the {@link #commit()} method should be used to commit the message.
     *
     * @param message the business layer message to send.
     * @param variableLength the total size of all the variable length and group fields in the message including their
     *                       headers. Aka the total length of the message minus it's block length.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @see #tryClaim(MessageEncoderFlyweight)
     */
    long tryClaim(MessageEncoderFlyweight message, int variableLength);

    /**
     * Commit a message that has been claimed. Do not overlap sending other messages or polling the FixLibrary
     * with claiming and committing your own message - just claim and commit it immediately. If an error happens during
     * the initialization of the message then you should call {@link #abort()}
     *
     * @see #tryClaim(MessageEncoderFlyweight)
     * @see #tryClaim(MessageEncoderFlyweight, int)
     * @see #abort()
     */
    void commit();

    /**
     * Abort a message that has been claimed. If an error happens when initialising a message flyweight after a
     * call to <code>tryClaim()</code> then this method can be called in order to abort the message and not send it.
     *
     * @see #tryClaim(MessageEncoderFlyweight)
     * @see #tryClaim(MessageEncoderFlyweight, int)
     * @see #abort()
     */
    void abort();

    /**
     * Try to send a sequence message indicating the current sent sequence number position. This can be combined
     * with {@link #nextSentSeqNo(long)} in order to move the sent sequence number forward in agreement with the
     * exchange.
     *
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     */
    long trySendSequence();

    /**
     * Disconnect your session, providing a reason. This is an immediate TCP disconnect with no Terminate message
     * sent.
     *
     * @param reason the reason you disconnected.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     */
    long requestDisconnect(DisconnectReason reason);

    // -----------------------------------------------
    // Accessors
    // -----------------------------------------------

    /**
     * Gets the Artio connectionId of the current connection for this session.
     *
     * @return the Artio connectionId of the current connection for this session.
     */
    long connectionId();

    /**
     * Gets the current State of this session.
     *
     * @return the current State of this session.
     */
    State state();

    /**
     * Gets the next sequence number to be used when sending a new business layer message.
     *
     * @return the next sequence number to be used when sending  a new business layer message.
     */
    long nextSentSeqNo();

    /**
     * Sets the next sequence number to be used when sending a new business layer message.
     *
     * @param nextSentSeqNo the next sequence number to be used when sending  a new business layer message.
     */
    void nextSentSeqNo(long nextSentSeqNo);

    /**
     * Gets the next sequence number to be expected when receiving a new business layer message.
     *
     * @return the next sequence number to be expected when receiving  a new business layer message.
     */
    long nextRecvSeqNo();

    /**
     * Sets the next sequence number to be expected when receiving a new business layer message.
     *
     * @param nextRecvSeqNo the next sequence number to be expected when receiving  a new business layer message.
     */
    void nextRecvSeqNo(long nextRecvSeqNo);

    /**
     * Check if a message can be sent. This is when you're in the ESTABLISHED or AWAITING_KEEPALIVE state.
     *
     * @return true if a message can be sent, false otherwise
     */
    boolean canSendMessage();

    /**
     * Gets the key associated with this connection. This uniquely identifies it over multiple establishments
     *
     * @return the key associated with this connection.
     */
    FixPKey key();


    /**
     * Returns true if a connection is connected at this point in time.
     *
     * @return true if a connection is connected at this point in time.
     */
    boolean isConnected();

    long counterpartyKeepAliveIntervalInMs();

    long ourKeepAliveIntervalInMs();
}
