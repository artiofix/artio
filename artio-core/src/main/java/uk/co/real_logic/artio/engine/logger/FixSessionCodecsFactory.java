/*
 * Copyright 2019 Monotonic Ltd.
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

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.ManageSessionDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.messages.MessageHeaderDecoder.ENCODED_LENGTH;

public class FixSessionCodecsFactory implements ControlledFragmentHandler
{
    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final ManageSessionDecoder manageSession = new ManageSessionDecoder();

    private final Map<String, FixReplayerCodecs> fixDictionaryClassToIndex = new HashMap<>();
    private final Long2ObjectHashMap<FixReplayerCodecs> sessionIdToFixDictionaryIndex = new Long2ObjectHashMap<>();
    private final Function<String, FixReplayerCodecs> makeFixReplayerCodecs = this::makeFixReplayerCodecs;
    private final EpochNanoClock nanoClock;

    final UtcTimestampEncoder timestampEncoder;

    public FixSessionCodecsFactory(final EpochNanoClock nanoClock, final EpochFractionFormat epochFractionFormat)
    {
        this.nanoClock = nanoClock;
        timestampEncoder = new UtcTimestampEncoder(epochFractionFormat);
    }

    @Override
    public Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        messageHeader.wrap(buffer, offset);

        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();

        if (messageHeader.templateId() == ManageSessionDecoder.TEMPLATE_ID)
        {
            manageSession.wrap(buffer, offset + ENCODED_LENGTH, blockLength, version);

            // Skip over variable length fields
            manageSession.skipLocalCompId();
            manageSession.skipLocalSubId();
            manageSession.skipLocalLocationId();
            manageSession.skipRemoteCompId();
            manageSession.skipRemoteSubId();
            manageSession.skipRemoteLocationId();
            manageSession.skipAddress();
            manageSession.skipUsername();
            manageSession.skipPassword();

            onDictionary(manageSession.session(), manageSession.fixDictionary());
        }

        return CONTINUE;
    }

    private void onDictionary(final long sessionId, final String fixDictionaryClassName)
    {
        final FixReplayerCodecs fixReplayerCodecs = fixDictionaryClassToIndex.computeIfAbsent(
            fixDictionaryClassName, makeFixReplayerCodecs);
        final FixReplayerCodecs previousIndex = sessionIdToFixDictionaryIndex.get(sessionId);
        // NB: this could potentially changes over time.
        if (previousIndex != fixReplayerCodecs)
        {
            sessionIdToFixDictionaryIndex.put(sessionId, fixReplayerCodecs);
        }
    }

    private FixReplayerCodecs makeFixReplayerCodecs(final String fixDictionaryName)
    {
        return new FixReplayerCodecs(FixDictionary.find(fixDictionaryName), timestampEncoder, nanoClock);
    }

    FixReplayerCodecs get(final long sessionId)
    {
        return sessionIdToFixDictionaryIndex.get(sessionId);
    }
}
