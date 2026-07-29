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
package uk.co.real_logic.artio.binary_entrypoint;

import b3.entrypoint.fixp.sbe.CancelOnDisconnectType;
import b3.entrypoint.fixp.sbe.TerminationCode;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.fixp.AbstractFixPSequenceExtractor;
import uk.co.real_logic.artio.fixp.FixPCancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.messages.ThrottleConfigurationStatus;
import uk.co.real_logic.artio.session.Session;

/**
 * Represents a Session Connection of the Binary Entrypoint protocol.
 * This is a FIXP session protocol with SBE encoded binary messages. It is very similar to CME's iLink3 protocol.
 */
public interface BinaryEntryPointConnection extends FixPConnection
{
    /**
     * Can be used to create an offline context for a session where you don't know
     * the session version id of the next logon.
     */
    long NEXT_SESSION_VERSION_ID = AbstractFixPSequenceExtractor.NEXT_SESSION_VERSION_ID;

    // -----------------------------------------------
    // Operations
    // -----------------------------------------------

    /**
     * Terminate the connection. This method sends a terminate message. Note that the terminate message isn't
     * guaranteed to be sent at the point that the message returns but the connection will retry on it's library's
     * duty-cycle if it hasn't been sent. The session won't be fully terminated until the connection has received a
     * reply from its counter-party or timed out.
     *
     * @param terminationCode the terminate code to use when sending the terminate message.
     */
    void terminate(TerminationCode terminationCode);

    /**
     * Send a notApplied message to the client.
     *
     * @param fromSeqNo the first applied sequence number.
     * @param count How many messages have not been applied.
     * @return position.
     */
    long trySendNotApplied(long fromSeqNo, long count);

    // -----------------------------------------------
    // Accessors
    // -----------------------------------------------

    /**
     * Gets the session id. This corresponds to the session id within the Negotiate or Establish message used in the
     * logon process.
     *
     * @return the session id.
     */
    long sessionId();

    /**
     * Gets the session version id. This corresponds to the session version id within the Negotiate or Establish
     * message used in the logon process.
     *
     * @return the session id.
     */
    long sessionVerId();

    /**
     * Initiate the finish sending process within BinaryEntryPoint. This methods sends a FinishedSending message
     * to its counter-party and awaits the receipt of the finished sending message from the counter-party. This
     * messages agree the end of the sequence of messages of the current session version id. See the Binary EntryPoint
     * specification for more details on the finished sending protocol.
     *
     * Once this method has been invoked then you cannot send any messages via the
     * {@link #tryClaim(MessageEncoderFlyweight)} method. For this method to be successful you need to have an
     * established session or one that has received a finished sending message from its counterparty.
     */
    void finishSending();

    /**
     * {@inheritDoc}
     */
    @Override
    BinaryEntryPointKey key();

    /**
     * Gets the cancel on disconnect configuration for this session. This is the same field value that has been sent in
     * the Establish message.
     *
     * @return the cancel on disconnect configuration for this session.
     * @see #codTimeoutWindow()
     * @see FixPCancelOnDisconnectTimeoutHandler
     */
    CancelOnDisconnectType cancelOnDisconnectType();

    /**
     * Gets the cancel on disconnect timeout window in milliseconds. This is the field value taken from the establish
     * message, it cannot be over 60 seconds.
     *
     * @return the cancel on disconnect timeout window in milliseconds.
     * @see #cancelOnDisconnectType()
     * @see FixPCancelOnDisconnectTimeoutHandler
     */
    long codTimeoutWindow();

    /**
     * Override Artio's message throttle configuration for a given session.
     *
     * @param throttleWindowInMs the time window to apply the throttle over.
     * @param throttleLimitOfMessages the maximum number of messages that can be received within the time window.
     * @return a reply object that represents the state of the operation.
     * @throws IllegalArgumentException if either parameter is &lt; 1.
     * @see uk.co.real_logic.artio.engine.EngineConfiguration#enableMessageThrottle(int, int)
     */
    Reply<ThrottleConfigurationStatus> throttleMessagesAt(int throttleWindowInMs, int throttleLimitOfMessages);

    /**
     * Gets whether a replay is currently happening or not.
     *
     * Similar semantics to {@link Session#isReplaying()}.
     *
     * @return true if a replay is currently happening.
     */
    boolean isReplaying();
}
