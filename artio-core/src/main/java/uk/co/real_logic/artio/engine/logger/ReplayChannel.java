/*
 * Copyright 2020 Adaptive Financial Consulting Ltd.
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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns replay operations for a given connection. It maintains a queue and a current replay operation.
 */
class ReplayChannel
{
    private ReplayerSession session;
    private Deque<EnqueuedReplay> enqueuedReplays;

    void enqueueReplay(final EnqueuedReplay enqueuedReplay)
    {
        if (enqueuedReplays == null)
        {
            enqueuedReplays = new ArrayDeque<>();
        }

        enqueuedReplays.addLast(enqueuedReplay);
    }

    void reEnqueueReplay(final EnqueuedReplay enqueuedReplay)
    {
        enqueuedReplays.addFirst(enqueuedReplay);
    }

    public int enqueuedReplayCount()
    {
        final Deque<EnqueuedReplay> enqueuedReplays = this.enqueuedReplays;
        return enqueuedReplays == null ? 0 : enqueuedReplays.size();
    }

    // Can be null if back-pressured trying to create a new ReplayerSession
    void startReplay(final ReplayerSession session)
    {
        this.session = session;
    }

    EnqueuedReplay pollReplay()
    {
        final Deque<EnqueuedReplay> enqueuedReplays = this.enqueuedReplays;
        return enqueuedReplays == null ? null : enqueuedReplays.pollFirst();
    }

    boolean attemptReplay()
    {
        return session == null || session.attemptReplay();
    }

    void closeNow()
    {
        if (session != null)
        {
            session.closeNow();
        }
    }

    // true if safe to remove immediately
    public boolean startClose()
    {
        if (session != null)
        {
            session.startClose();
            return false;
        }

        return true;
    }
}
