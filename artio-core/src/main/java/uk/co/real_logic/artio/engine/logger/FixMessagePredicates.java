/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.LongHashSet;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.engine.framer.MessageTypeExtractor;
import uk.co.real_logic.artio.messages.FixMessageDecoder;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.BufferAsciiSequence;
import uk.co.real_logic.artio.util.MessageTypeEncoding;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataSinceVersion;

/**
 * Filters to be used in conjunction with {@link FixArchiveScanner}.
 */
public final class FixMessagePredicates
{
    private FixMessagePredicates()
    {
    }

    /**
     * Filter messages passed to consumer, only passing through messages that pass the predicate.
     *
     * @param consumer  the consumer to receive filtered messages.
     * @param predicate the predicate to filter messages.
     * @return a new composed consumer.
     */
    public static FixMessageConsumer filterBy(
        final FixMessageConsumer consumer, final FixMessagePredicate predicate)
    {
        return new FilterBy(consumer, predicate);
    }

    static class FilterBy implements FixMessageConsumer
    {
        final FixMessageConsumer consumer;
        final FixMessagePredicate predicate;

        FilterBy(final FixMessageConsumer consumer, final FixMessagePredicate predicate)
        {
            this.consumer = consumer;
            this.predicate = predicate;
        }

        @Override
        public void reset()
        {
            consumer.reset();
            predicate.reset();
        }

        @Override
        public void onMessage(
            final FixMessageDecoder message,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final ArtioLogHeader header)
        {
            final int actingVersion = message.sbeSchemaVersion();
            final int actingBlockLength = message.sbeBlockLength();
            final int initialOffset = message.offset();

            if (predicate.test(message))
            {
                // Rewrap incase the predicate.test() method has altered the limit()
                message.wrap(buffer, initialOffset, actingBlockLength, actingVersion);
                if (actingVersion >= metaDataSinceVersion())
                {
                    message.skipMetaData();
                }
                consumer.onMessage(message, buffer, offset, length, header);
            }
        }

        @Override
        public String toString()
        {
            return "FilterBy{" +
                "consumer=" + consumer +
                ", predicate=" + predicate +
                '}';
        }
    }

    /**
     * Filters a timestamp to be between these begin and end times.
     *
     * Timestamps filtered in precision of CommonConfiguration.clock().
     *
     * @param beginTimestampInclusive the message's timestamp must be &gt;= this value.
     * @param endTimestampExclusive the message's timestamp must be &lt; this value.
     * @return the resulting predicate
     */
    public static FixMessagePredicate between(
        final long beginTimestampInclusive,
        final long endTimestampExclusive)
    {
        return from(beginTimestampInclusive).and(to(endTimestampExclusive));
    }

    /**
     * Filters a timestamp from a given begin time.
     *
     * @param beginTimestampInclusive the message's timestamp must be &gt;= this value.
     * @return the resulting predicate
     */
    public static FixMessagePredicate from(final long beginTimestampInclusive)
    {
        return new From(beginTimestampInclusive);
    }

    static class From implements FixMessagePredicate
    {
        private final long beginTimestampInclusive;

        From(final long beginTimestampInclusive)
        {
            this.beginTimestampInclusive = beginTimestampInclusive;
        }

        @Override
        public boolean test(final FixMessageDecoder message)
        {
            return message.timestamp() >= beginTimestampInclusive;
        }

        long beginTimestampInclusive()
        {
            return beginTimestampInclusive;
        }

        @Override
        public String toString()
        {
            return "From{" +
                "beginTimestampInclusive=" + beginTimestampInclusive +
                '}';
        }
    }

    /**
     * Filters a timestamp to a given end time.
     *
     * Timestamps filtered in precision of CommonConfiguration.clock().
     *
     * @param endTimestampExclusive the message's timestamp must be &lt; this value.
     * @return the resulting predicate
     */
    public static FixMessagePredicate to(final long endTimestampExclusive)
    {
        return new To(endTimestampExclusive);
    }

    static class To implements FixMessagePredicate
    {
        private final long endTimestampExclusive;

        To(final long endTimestampExclusive)
        {
            this.endTimestampExclusive = endTimestampExclusive;
        }

        @Override
        public boolean test(final FixMessageDecoder message)
        {
            return message.timestamp() < endTimestampExclusive;
        }

        public long endTimestampExclusive()
        {
            return endTimestampExclusive;
        }
    }

    /**
     * Filter messages by the message type of their fix message.
     *
     * Timestamps filtered in precision of CommonConfiguration.clock().
     *
     * @param messageTypes the fix message type strings that you see in the message.
     * @return the resulting predicate
     */
    public static FixMessagePredicate messageTypeOf(final String... messageTypes)
    {
        final LongHashSet hashSet = new LongHashSet();
        Stream.of(messageTypes)
            .mapToLong(MessageTypeEncoding::packMessageType)
            .forEach(hashSet::add);
        return messageTypeOf(hashSet);
    }

    /**
     * Filter messages by the message type of their fix message.
     *
     * @param messageTypes the fix message types encoded as packed longs.
     * @return the resulting predicate.
     */
    public static FixMessagePredicate messageTypeOf(final long... messageTypes)
    {
        final LongHashSet hashSet = new LongHashSet();
        LongStream.of(messageTypes)
                  .forEach(hashSet::add);
        return messageTypeOf(hashSet);
    }

    private static FixMessagePredicate messageTypeOf(final LongHashSet hashSet)
    {
        return (message) ->
        {
            final long messageType = MessageTypeExtractor.getMessageType(message);
            return hashSet.contains(messageType);
        };
    }

    /**
     * Filter the fix message predicate by parsing the sender and target comp ids out of the message body.
     *
     * @param fixDictionary the fixDictionary to specify the version of the project.
     * @param senderCompId the sender comp id required in the message.
     * @param targetCompId the target comp id required in the message.
     * @return the resulting predicate.
     */
    public static FixMessagePredicate sessionOf(
        final FixDictionary fixDictionary,
        final String senderCompId,
        final String targetCompId)
    {
        return whereHeader(fixDictionary,
            senderCompIdOf(senderCompId).and(targetCompIdOf(targetCompId)));
    }

    public static Predicate<SessionHeaderDecoder> senderCompIdOf(final String senderCompId)
    {
        return headerMatches(
            senderCompId,
            HeaderField.SENDER_COMP_ID,
            SessionHeaderDecoder::senderCompID,
            SessionHeaderDecoder::senderCompIDLength,
            true);
    }

    public static Predicate<SessionHeaderDecoder> targetCompIdOf(final String targetCompId)
    {
        return headerMatches(
            targetCompId,
            HeaderField.TARGET_COMP_ID,
            SessionHeaderDecoder::targetCompID,
            SessionHeaderDecoder::targetCompIDLength,
            true);
    }

    public static Predicate<SessionHeaderDecoder> senderSubIdOf(final String senderSubId)
    {
        return headerMatchesConsistent(
            senderSubId, SessionHeaderDecoder::senderSubID, SessionHeaderDecoder::senderSubIDLength);
    }

    public static Predicate<SessionHeaderDecoder> targetSubIdOf(final String targetSubId)
    {
        return headerMatchesConsistent(
            targetSubId, SessionHeaderDecoder::targetSubID, SessionHeaderDecoder::targetSubIDLength);
    }

    public static Predicate<SessionHeaderDecoder> senderLocationIdOf(final String senderLocationId)
    {
        return headerMatchesConsistent(
            senderLocationId, SessionHeaderDecoder::senderLocationID, SessionHeaderDecoder::senderLocationIDLength);
    }

    public static Predicate<SessionHeaderDecoder> targetLocationIdOf(final String targetLocationId)
    {
        return headerMatchesConsistent(
            targetLocationId, SessionHeaderDecoder::targetLocationID, SessionHeaderDecoder::targetLocationIDLength);
    }

    public static Predicate<SessionHeaderDecoder> headerMatches(
        final String value,
        final Function<SessionHeaderDecoder, char[]> charExtractor,
        final ToIntFunction<SessionHeaderDecoder> lengthExtractor)
    {
        return headerMatches(value, HeaderField.NOT_OPTIMISED, charExtractor, lengthExtractor, false);
    }

    private static Predicate<SessionHeaderDecoder> headerMatchesConsistent(
        final String value,
        final Function<SessionHeaderDecoder, char[]> charExtractor,
        final ToIntFunction<SessionHeaderDecoder> lengthExtractor)
    {
        return headerMatches(value, HeaderField.NOT_OPTIMISED, charExtractor, lengthExtractor, true);
    }

    private static Predicate<SessionHeaderDecoder> headerMatches(
        final String value,
        final HeaderField headerField,
        final Function<SessionHeaderDecoder, char[]> charExtractor,
        final ToIntFunction<SessionHeaderDecoder> lengthExtractor,
        final boolean sessionConsistent)
    {
        return new HeaderMatches(value, headerField, sessionConsistent, charExtractor, lengthExtractor);
    }

    abstract static class HeaderPredicate implements Predicate<SessionHeaderDecoder>
    {
        private final boolean sessionConsistent;

        protected HeaderPredicate(final boolean sessionConsistent)
        {
            this.sessionConsistent = sessionConsistent;
        }

        @Override
        public Predicate<SessionHeaderDecoder> and(final Predicate<? super SessionHeaderDecoder> other)
        {
            if (other instanceof HeaderPredicate)
            {
                return new CompositeHeaderPredicate(this, (HeaderPredicate)other)
                {
                    @Override
                    public boolean test(final SessionHeaderDecoder sessionHeaderDecoder)
                    {
                        return left.test(sessionHeaderDecoder) && right.test(sessionHeaderDecoder);
                    }
                };
            }
            else
            {
                return Predicate.super.and(other);
            }
        }

        @Override
        public Predicate<SessionHeaderDecoder> or(final Predicate<? super SessionHeaderDecoder> other)
        {
            if (other instanceof HeaderPredicate)
            {
                return new CompositeHeaderPredicate(this, (HeaderPredicate)other)
                {
                    @Override
                    public boolean test(final SessionHeaderDecoder sessionHeaderDecoder)
                    {
                        return left.test(sessionHeaderDecoder) || right.test(sessionHeaderDecoder);
                    }
                };
            }
            else
            {
                return Predicate.super.or(other);
            }
        }

        @Override
        public Predicate<SessionHeaderDecoder> negate()
        {
            final HeaderPredicate delegate = this;
            return new HeaderPredicate(delegate.sessionConsistent)
            {
                @Override
                public boolean test(final SessionHeaderDecoder sessionHeaderDecoder)
                {
                    return !delegate.test(sessionHeaderDecoder);
                }
            };
        }

        public boolean isSessionConsistent()
        {
            return sessionConsistent;
        }
    }

    abstract static class CompositeHeaderPredicate extends HeaderPredicate
    {
        final HeaderPredicate left;
        final HeaderPredicate right;

        CompositeHeaderPredicate(final HeaderPredicate left, final HeaderPredicate right)
        {
            super(left.isSessionConsistent() && right.isSessionConsistent());
            this.left = left;
            this.right = right;
        }

    }

    static class HeaderMatches extends HeaderPredicate
    {
        final char[] expectedChars;
        final HeaderField headerField;
        final String value;

        private final Function<SessionHeaderDecoder, char[]> charExtractor;
        private final ToIntFunction<SessionHeaderDecoder> lengthExtractor;

        HeaderMatches(
            final String value,
            final HeaderField headerField,
            final boolean sessionConsistent,
            final Function<SessionHeaderDecoder, char[]> charExtractor,
            final ToIntFunction<SessionHeaderDecoder> lengthExtractor)
        {
            super(sessionConsistent);
            expectedChars = value.toCharArray();

            this.value = value;
            this.headerField = headerField;
            this.charExtractor = charExtractor;
            this.lengthExtractor = lengthExtractor;
        }

        @Override
        public boolean test(final SessionHeaderDecoder header)
        {
            final char[] actualChars = charExtractor.apply(header);
            final int length = lengthExtractor.applyAsInt(header);
            return CodecUtil.equals(actualChars, expectedChars, length);
        }
    }

    public static FixMessagePredicate whereHeader(
        final FixDictionary fixDictionary,
        final Predicate<SessionHeaderDecoder> matches)
    {
        if (matches instanceof HeaderPredicate)
        {
            if (((HeaderPredicate)matches).isSessionConsistent())
            {
                return new SessionConsistentWhereHeader(fixDictionary, matches);
            }
        }
        return new WhereHeader(fixDictionary, matches);
    }

    static class WhereHeader implements FixMessagePredicate
    {
        private final Predicate<SessionHeaderDecoder> matches;
        private final SessionHeaderDecoder header;
        private final AsciiBuffer asciiBuffer;

        WhereHeader(final FixDictionary fixDictionary, final Predicate<SessionHeaderDecoder> matches)
        {
            header = fixDictionary.makeHeaderDecoder();
            this.matches = matches;
            asciiBuffer = new MutableAsciiBuffer();
        }

        @Override
        public boolean test(final FixMessageDecoder message)
        {
            final DirectBuffer buffer = message.buffer();
            final int length = message.bodyLength();
            final int bodyOffset = message.limit() + FixMessageDecoder.bodyHeaderLength();
            asciiBuffer.wrap(buffer);
            header.decode(asciiBuffer, bodyOffset, length);
            return matches.test(header);
        }

        public Predicate<SessionHeaderDecoder> matches()
        {
            return matches;
        }
    }

    // Applies a WhereHeader and caches the session ids
    static class SessionConsistentWhereHeader extends WhereHeader
    {
        private final LongHashSet matchingIds = new LongHashSet();
        private final LongHashSet rejectedIds = new LongHashSet();

        SessionConsistentWhereHeader(
            final FixDictionary fixDictionary, final Predicate<SessionHeaderDecoder> matches)
        {
            super(fixDictionary, matches);
        }

        @Override
        public void reset()
        {
            matchingIds.clear();
            rejectedIds.clear();
        }

        @Override
        public boolean test(final FixMessageDecoder message)
        {
            final LongHashSet matchingIds = this.matchingIds;
            final LongHashSet rejectedIds = this.rejectedIds;

            final long sessionId = message.session();
            if (matchingIds.contains(sessionId))
            {
                return true;
            }

            if (rejectedIds.contains(sessionId))
            {
                return false;
            }

            final boolean result = super.test(message);
            if (result)
            {
                matchingIds.add(sessionId);
            }
            else
            {
                rejectedIds.add(sessionId);
            }
            return result;
        }
    }

    /**
     * Filter the fix message by checking the assigned session id field is equal to the given parameter.
     *
     * @param sessionId the surrogate session id key.
     * @return the resulting predicate.
     */
    public static FixMessagePredicate sessionOf(final long sessionId)
    {
        return (message) -> message.session() == sessionId;
    }

    public static FixMessagePredicate bodyMatches(final Pattern pattern)
    {
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(1024);
        final BufferAsciiSequence sequence = new BufferAsciiSequence();
        return message ->
        {
            final int length = message.bodyLength();
            buffer.checkLimit(length);
            message.getBody(buffer, 0, length);
            sequence.wrap(buffer, 0, length);
            return pattern.matcher(sequence).matches();
        };
    }

    public static FixMessagePredicate alwaysTrue()
    {
        return message -> true;
    }
}
