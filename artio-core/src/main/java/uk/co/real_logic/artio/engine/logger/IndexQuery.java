/*
 * Copyright 2021 Monotonic Ltd.
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

class IndexQuery
{
    static final long NO_BEGIN = Long.MIN_VALUE;
    static final long NO_END = Long.MAX_VALUE;

    private long beginTimestampInclusive = Long.MIN_VALUE;
    private long endTimestampExclusive = Long.MAX_VALUE;

    void from(final long beginTimestampInclusive)
    {
        final long existingBeginTimestampInclusive = this.beginTimestampInclusive;
        if (existingBeginTimestampInclusive == Long.MIN_VALUE ||
            existingBeginTimestampInclusive > beginTimestampInclusive)
        {
            this.beginTimestampInclusive = beginTimestampInclusive;
        }
    }

    void to(final long endTimestampExclusive)
    {
        final long existingEndTimestampInclusive = this.endTimestampExclusive;
        if (existingEndTimestampInclusive == Long.MAX_VALUE ||
            existingEndTimestampInclusive < endTimestampExclusive)
        {
            this.endTimestampExclusive = endTimestampExclusive;
        }
    }

    boolean needed()
    {
        return beginTimestampInclusive != NO_BEGIN ||
            endTimestampExclusive != NO_END;
    }

    @Override
    public String toString()
    {
        return "IndexPlan{" +
            "beginTimestampInclusive=" + beginTimestampInclusive +
            ", endTimestampExclusive=" + endTimestampExclusive +
            '}';
    }

    public long beginTimestampInclusive()
    {
        return beginTimestampInclusive;
    }

    public long endTimestampExclusive()
    {
        return endTimestampExclusive;
    }
}
