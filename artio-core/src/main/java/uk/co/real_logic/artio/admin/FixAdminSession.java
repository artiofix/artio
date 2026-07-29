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
package uk.co.real_logic.artio.admin;

import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.GatewayProcess;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.ParsedAddress;
import uk.co.real_logic.artio.session.Session;

public class FixAdminSession implements SessionInfo
{
    private final long lastLogonTime;
    private final String connectedHost;
    private final CompositeKey sessionKey;

    private final long sessionId;
    private final long connectionId;
    private final int lastReceivedMsgSeqNum;
    private final int lastSentMsgSeqNum;
    private final int connectedPort;
    private final int sequenceIndex;
    private final boolean isSlow;

    public FixAdminSession(
        final long sessionId,
        final long connectionId,
        final int lastReceivedMsgSeqNum,
        final int lastSentMsgSeqNum,
        final long lastLogonTime,
        final int sequenceIndex,
        final boolean isSlow,
        final String address,
        final CompositeKey sessionKey)
    {
        this.sessionId = sessionId;
        this.connectionId = connectionId;
        this.lastReceivedMsgSeqNum = lastReceivedMsgSeqNum;
        this.lastSentMsgSeqNum = lastSentMsgSeqNum;
        this.lastLogonTime = lastLogonTime;
        this.isSlow = isSlow;
        final ParsedAddress parsed = ParsedAddress.parse(address);
        this.connectedHost = parsed.host();
        this.connectedPort = parsed.port();
        this.sessionKey = sessionKey;
        this.sequenceIndex = sequenceIndex;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long sessionId()
    {
        return sessionId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompositeKey sessionKey()
    {
        return sessionKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    /**
     * Get the identification number of the connection in question.
     *
     * @return the identification number of the connection in question.
     */
    public long connectionId()
    {
        return connectionId;
    }

    /**
     * Get the address of the remote host that your session is connected to.
     * <p>
     * If this is an offline session then this method will return <code>""</code>.
     *
     * @return the address of the remote host that your session is connected to.
     * @see Session#connectedPort()
     */
    public String connectedHost()
    {
        return connectedHost;
    }

    /**
     * Get the port of the remote host that your session is connected to.
     * <p>
     * If this is an offline session then this method will return {@link Session#UNKNOWN}
     *
     * @return the port of the remote host that your session is connected to.
     * @see Session#connectedHost()
     */
    public int connectedPort()
    {
        return connectedPort;
    }

    /**
     * This returns the time of the last received logon message for the current session. The source
     * of time here is configured from your {@link CommonConfiguration#epochNanoClock(EpochNanoClock)}.
     * This defaults to nanoseconds but it can be any precision that you configure.
     *
     * @return the time of the last received logon message for the current session.
     */
    public long lastLogonTime()
    {
        return lastLogonTime;
    }

    /**
     * Get the sequence number of the last message to be received by this session.
     *
     * @return the sequence number of the last message to be received by this session.
     */
    public int lastReceivedMsgSeqNum()
    {
        return lastReceivedMsgSeqNum;
    }

    /**
     * Get the sequence number of the last message to be sent from this session.
     *
     * @return the sequence number of the last message to be sent from this session.
     */
    public int lastSentMsgSeqNum()
    {
        return lastSentMsgSeqNum;
    }

    /**
     * Get whether the session is connected or not.
     *
     * @return true if the session is connected, false otherwise.
     */
    public boolean isConnected()
    {
        return connectionId != GatewayProcess.NO_CONNECTION_ID;
    }

    /**
     * Gets whether the session is slow or not. If the session is not currently connected then this method will
     * return true.
     *
     * @see <a href="https://github.com/artiofix/artio/wiki/Performance-and-Fairness#slow-consumer-support">
     *     Slow Consumer Support.</a>
     * @return true if slow, false otherwise.
     */
    public boolean isSlow()
    {
        return isSlow;
    }

    public String toString()
    {
        return "FixAdminSession{" +
            "lastLogonTime=" + lastLogonTime +
            ", connectedHost='" + connectedHost + '\'' +
            ", sessionKey=" + sessionKey +
            ", sessionId=" + sessionId +
            ", connectionId=" + connectionId +
            ", lastReceivedMsgSeqNum=" + lastReceivedMsgSeqNum +
            ", lastSentMsgSeqNum=" + lastSentMsgSeqNum +
            ", connectedPort=" + connectedPort +
            ", sequenceIndex=" + sequenceIndex +
            ", isSlow=" + isSlow +
            '}';
    }
}
