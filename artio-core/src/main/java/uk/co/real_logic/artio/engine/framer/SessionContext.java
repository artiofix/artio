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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

/**
 * Context information about a FIX session, that persists across restarts.
 */
class SessionContext implements SessionInfo
{
    private final CompositeKey compositeKey;
    private final long sessionId;
    private final FixContexts fixContexts;

    // onSequenceReset() will be called upon logon or not depending upon whether this is a persistent
    // session or not.
    // Variable only written to on the Framer thread but can be read on other threads via the
    // SessionInfo interface.
    private volatile int sequenceIndex;
    private final int initialSequenceIndex;
    private final boolean reproductionEnabled;

    private long lastLogonTimeInNs;
    private long lastSequenceResetTimeInNs;
    private FixDictionary lastFixDictionary;
    private int filePosition;

    SessionContext(
        final CompositeKey compositeKey,
        final long sessionId,
        final int sequenceIndex,
        final long lastLogonTimeInNs,
        final long lastSequenceResetTimeInNs,
        final FixContexts fixContexts,
        final int filePosition,
        final int initialSequenceIndex,
        final FixDictionary lastFixDictionary,
        final boolean reproductionEnabled)
    {
        this.compositeKey = compositeKey;
        this.sessionId = sessionId;
        this.sequenceIndex = sequenceIndex;
        this.initialSequenceIndex = initialSequenceIndex;
        this.reproductionEnabled = reproductionEnabled;
        lastLogonTimeInNs(lastLogonTimeInNs);
        this.lastSequenceResetTimeInNs = lastSequenceResetTimeInNs;
        this.fixContexts = fixContexts;
        this.filePosition = filePosition;
        this.lastFixDictionary = lastFixDictionary;
    }

    private void lastLogonTimeInNs(final long lastLogonTime)
    {
        this.lastLogonTimeInNs = lastLogonTime;
    }

    void onSequenceReset(final long resetTimeInNs)
    {
        final int newSequenceIndex = sequenceIndex == UNKNOWN_SEQUENCE_INDEX ? initialSequenceIndex : sequenceIndex + 1;

        if (reproductionEnabled && (sequenceIndex != UNKNOWN_SEQUENCE_INDEX))
        {
            // Don't increment the sequence index during the
            return;
        }

        onSequenceIndex(resetTimeInNs, newSequenceIndex);
    }

    void onSequenceIndex(final long resetTimeInNs, final int sequenceIndex)
    {
        lastSequenceResetTimeInNs = resetTimeInNs;
        this.sequenceIndex = sequenceIndex;
        save();
    }

    void updateAndSaveFrom(final Session session)
    {
        updateFrom(session);
        save();
    }

    void ensureFixDictionary(final FixDictionary fixDictionary)
    {
        if (lastFixDictionary != fixDictionary)
        {
            this.lastFixDictionary = fixDictionary;
            save();
        }
    }

    private void save()
    {
        fixContexts.updateSavedData(this, filePosition);
    }

    void filePosition(final int filePosition)
    {
        this.filePosition = filePosition;
    }

    void updateFrom(final Session session)
    {
        sequenceIndex = session.sequenceIndex();
        lastLogonTimeInNs(session.lastLogonTimeInNs());
        lastSequenceResetTimeInNs = session.lastSequenceResetTimeInNs();
    }

    void onLogon(final boolean resetSeqNum, final long timeInNs, final FixDictionary fixDictionary)
    {
        lastFixDictionary = fixDictionary;
        lastLogonTimeInNs(timeInNs);
        // increment if we're going to reset the sequence number or if it's persistent
        // sequence numbers and it's the first time we're logging on.
        if (resetSeqNum || sequenceIndex == SessionContext.UNKNOWN_SEQUENCE_INDEX)
        {
            onSequenceReset(timeInNs);
        }
        else
        {
            // onSequenceReset also saves.
            save();
        }
    }

    @Override
    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    @Override
    public long sessionId()
    {
        return sessionId;
    }

    @Override
    public CompositeKey sessionKey()
    {
        return compositeKey;
    }

    public long lastSequenceResetTime()
    {
        return lastSequenceResetTimeInNs;
    }

    public long lastLogonTimeInNs()
    {
        return lastLogonTimeInNs;
    }

    public FixDictionary lastFixDictionary()
    {
        return lastFixDictionary;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final SessionContext that = (SessionContext)o;

        return sessionId == that.sessionId;
    }

    @Override
    public int hashCode()
    {
        return (int)(sessionId ^ (sessionId >>> 32));
    }

    @Override
    public String toString()
    {
        return "SessionContext{" +
            "sessionId=" + sessionId +
            ", sequenceIndex=" + sequenceIndex +
            ", compositeKey=" + compositeKey +
            ", lastLogonTime=" + lastLogonTimeInNs +
            ", lastSequenceResetTimeInNs=" + lastSequenceResetTimeInNs +
            '}';
    }
}
