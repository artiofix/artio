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
package uk.co.real_logic.artio.system_tests;

import org.agrona.collections.IntHashSet;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.status.CountersReader;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.engine.FixEngine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static uk.co.real_logic.artio.FixCounters.FixCountersId.CURRENT_REPLAY_COUNT_TYPE_ID;

class ReplayCountChecker implements Runnable
{

    static ReplayCountChecker start(
        final FixEngine engine, final TestSystem testSystem, final int failureThresHold)
    {
        final ReplayCountChecker replayCountChecker = new ReplayCountChecker(engine, failureThresHold);
        testSystem.addOperation(replayCountChecker);
        return replayCountChecker;
    }

    private final int failureThresHold;
    private final IntHashSet replayCounterIds;
    private final CountersReader countersReader;
    private final LongHashSet failureCounts = new LongHashSet();

    ReplayCountChecker(final FixEngine engine, final int failureThresHold)
    {
        this.failureThresHold = failureThresHold;

        countersReader = engine.configuration().aeronArchiveContext().aeron().countersReader();
        replayCounterIds = FixCounters.lookupCounterIds(CURRENT_REPLAY_COUNT_TYPE_ID, countersReader);
        assertThat(replayCounterIds, hasSize(greaterThan(0)));
    }

    @Override
    public void run()
    {
        for (final int replayCounterId : replayCounterIds)
        {
            final long currentReplayCount = countersReader.getCounterValue(replayCounterId);
            if (currentReplayCount > failureThresHold)
            {
                failureCounts.add(currentReplayCount);
            }
        }
    }

    void assertBelowThreshold()
    {
        assertThat("Too many concurrent replays in flight: " + failureCounts, failureCounts, hasSize(0));
    }
}
