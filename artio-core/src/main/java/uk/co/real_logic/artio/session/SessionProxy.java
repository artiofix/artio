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
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.DisconnectReason;

/**
 * A proxy that allows users to hook the sending of FIX session protocol messages through an external system. This can
 * be used to integrate Artio into a clustering system. You implement the SessionProxy interface to write the
 * session protocol messages into your clustering system. This enables you to achieve a strong consensus on the protocol
 * messages before using the <code>SessionWriter</code> class to write them back out.
 */
public interface SessionProxy
{
    void fixDictionary(FixDictionary dictionary);

    void setupSession(long sessionId, CompositeKey sessionKey);

    void connectionId(long connectionId);

    long sendResendRequest(
        int msgSeqNo,
        int beginSeqNo,
        int endSeqNo,
        int sequenceIndex,
        int lastMsgSeqNumProcessed);

    long sendRequestDisconnect(long connectionId, DisconnectReason reason);

    long sendLogon(
        int msgSeqNo,
        int heartbeatIntervalInS,
        String username,
        String password,
        boolean resetSeqNumFlag,
        int sequenceIndex,
        int lastMsgSeqNumProcessed,
        CancelOnDisconnectOption cancelOnDisconnectOption,
        int cancelOnDisconnectTimeoutWindowInMs);

    long sendLogout(int msgSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed, byte[] text);

    long sendLogout(
        int msgSeqNo, int sequenceIndex, int rejectReason, int lastMsgSeqNumProcessed);

    long sendLowSequenceNumberLogout(
        int msgSeqNo,
        int expectedSeqNo,
        int receivedSeqNo,
        int sequenceIndex,
        int lastMsgSeqNumProcessed);

    long sendIncorrectBeginStringLogout(
        int msgSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendNegativeHeartbeatLogout(
        int msgSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendReceivedMessageWithoutSequenceNumber(
        int msgSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendRejectWhilstNotLoggedOn(
        int msgSeqNo, RejectReason reason, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendHeartbeat(int msgSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendHeartbeat(
        int msgSeqNo,
        char[] testReqId,
        int testReqIdLength,
        int sequenceIndex,
        int lastMsgSeqNumProcessed);

    long sendReject(
        int msgSeqNo,
        int refSeqNum,
        int refTagId,
        char[] refMsgType,
        int refMsgTypeLength,
        int rejectReason,
        int sequenceIndex,
        int lastMsgSeqNumProcessed);

    long sendTestRequest(
        int msgSeqNo, CharSequence testReqID, int sequenceIndex, int lastMsgSeqNumProcessed);

    long sendSequenceReset(
        int msgSeqNo, int newSeqNo, int sequenceIndex, int lastMsgSeqNumProcessed);

    void libraryConnected(boolean libraryConnected);

    boolean seqNumResetRequested();

    long sendCancelOnDisconnectTrigger(long id, long timeInNs);

    long sendValidResendRequest(
        long sessionId,
        long connectionId,
        long beginSequenceNumber,
        long endSequenceNumber,
        int sequenceIndex,
        long correlationId,
        long overriddenBeginSequenceNumber,
        DirectBuffer bodyBuffer,
        int bodyOffset,
        int bodyLength);

    /**
     * Determines whether this implementation is asynchronous, for example, that it round-trips a message
     * via an external cluster system and then writes out the message via a follower session.
     *
     * @return true if asynchronous, false otherwise.
     */
    boolean isAsync();
}
