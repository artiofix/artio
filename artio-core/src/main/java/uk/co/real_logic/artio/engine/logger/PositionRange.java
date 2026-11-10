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

class PositionRange
{
    private final long startPosition;
    private final long endPosition;

    PositionRange(final long startPosition, final long endPosition)
    {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    public long startPosition()
    {
        return startPosition;
    }

    public long endPosition()
    {
        return endPosition;
    }

    @Override
    public String toString()
    {
        return "PositionRange{" +
            "startPosition=" + startPosition +
            ", endPosition=" + endPosition +
            '}';
    }
}
