/*
 * Copyright 2015-2025 Real Logic Limited.
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
package uk.co.real_logic.artio.system_tests;

import org.agrona.collections.Int2ObjectHashMap;
import org.hamcrest.Matcher;
import uk.co.real_logic.artio.Constants;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.session.Session;

import static org.hamcrest.Matchers.equalTo;
import static uk.co.real_logic.artio.Constants.LOGON_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.util.CustomMatchers.hasResult;

/**
 * Convenient dumb fix message wrapper for testing purposes.
 */
public class FixMessage extends Int2ObjectHashMap<String>
{
    private Session session;
    private int sequenceIndex;
    private long messageType;
    private MessageStatus status;
    private boolean valid;

    public FixMessage()
    {
    }

    public String msgType()
    {
        return get(Constants.MSG_TYPE);
    }

    public String testReqId()
    {
        return get(Constants.TEST_REQ_ID);
    }

    public boolean isLogon()
    {
        return LOGON_MESSAGE_AS_STR.equals(msgType());
    }

    public String possDup()
    {
        return get(Constants.POSS_DUP_FLAG);
    }

    public String gapFill()
    {
        return get(Constants.GAP_FILL_FLAG);
    }

    public int messageSequenceNumber()
    {
        return getInt(Constants.MSG_SEQ_NUM);
    }

    public int getInt(final int tag)
    {
        return Integer.parseInt(get(tag));
    }

    public Session session()
    {
        return session;
    }

    public void session(final Session session)
    {
        this.session = session;
    }

    public void sequenceIndex(final int sequenceIndex)
    {
        this.sequenceIndex = sequenceIndex;
    }

    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    public void messageType(final long messageType)
    {
        this.messageType = messageType;
    }

    public long messageType()
    {
        return messageType;
    }

    public void status(final MessageStatus status)
    {
        this.status = status;
    }

    public MessageStatus status()
    {
        return status;
    }

    public int lastMsgSeqNumProcessed()
    {
        return Integer.parseInt(get(Constants.LAST_MSG_SEQ_NUM_PROCESSED));
    }

    public void isValid(final boolean valid)
    {
        this.valid = valid;
    }

    public boolean isValid()
    {
        return valid;
    }

    public int cancelOnDisconnectType()
    {
        return getInt(Constants.CANCEL_ON_DISCONNECT_TYPE);
    }

    @Override
    public FixMessage clone()
    {
        final FixMessage theClone = new FixMessage();
        theClone.session(session);
        theClone.sequenceIndex(sequenceIndex);
        theClone.messageType(messageType);
        theClone.status(status);
        theClone.putAll(this);
        return theClone;
    }

    public void flipCompIds()
    {
        final String oldSenderCompId = get(Constants.SENDER_COMP_ID);
        final String oldTargetCompId = get(Constants.TARGET_COMP_ID);

        put(Constants.TARGET_COMP_ID, oldSenderCompId);
        put(Constants.SENDER_COMP_ID, oldTargetCompId);
    }

    @Override
    public String toString()
    {
        return "FixMessage{sequenceIndex=" + sequenceIndex +
            ", messageType=" + messageType +
            ", status=" + status +
            ", valid=" + valid +
            ", " + super.toString() +
            '}';
    }

    static Matcher<FixMessage> hasSequenceIndex(final int sequenceIndex)
    {
        return hasResult("sequenceIndex", FixMessage::sequenceIndex, equalTo(sequenceIndex));
    }

    static Matcher<FixMessage> hasMessageSequenceNumber(final int sequenceNumber)
    {
        return hasResult(
            "messageSequenceNumber",
            FixMessage::messageSequenceNumber,
            equalTo(sequenceNumber));
    }
}
