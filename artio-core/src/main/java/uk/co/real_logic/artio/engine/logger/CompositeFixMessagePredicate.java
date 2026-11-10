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

import uk.co.real_logic.artio.messages.FixMessageDecoder;

abstract class CompositeFixMessagePredicate implements FixMessagePredicate
{
    final FixMessagePredicate left;
    final FixMessagePredicate right;

    CompositeFixMessagePredicate(
        final FixMessagePredicate left, final FixMessagePredicate right)
    {
        this.left = left;
        this.right = right;
    }

    @Override
    public void reset()
    {
        left.reset();
        right.reset();
    }

    public FixMessagePredicate left()
    {
        return left;
    }

    public FixMessagePredicate right()
    {
        return right;
    }
}

class FixMessageAnd extends CompositeFixMessagePredicate
{
    FixMessageAnd(final FixMessagePredicate left, final FixMessagePredicate right)
    {
        super(left, right);
    }

    @Override
    public boolean test(final FixMessageDecoder message)
    {
        final int limit = message.limit();

        if (!left.test(message))
        {
            return false;
        }

        message.limit(limit);

        return right.test(message);
    }
}

class FixMessageOr extends CompositeFixMessagePredicate
{
    FixMessageOr(final FixMessagePredicate left, final FixMessagePredicate right)
    {
        super(left, right);
    }

    @Override
    public boolean test(final FixMessageDecoder message)
    {
        final int limit = message.limit();

        if (left.test(message))
        {
            return true;
        }

        message.limit(limit);

        return right.test(message);
    }
}
