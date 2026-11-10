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

import uk.co.real_logic.artio.session.CompositeKey;

class AdminCompositeKey implements CompositeKey
{
    private final String localCompId;
    private final String localSubId;
    private final String localLocationId;
    private final String remoteCompId;
    private final String remoteSubId;
    private final String remoteLocationId;

    AdminCompositeKey(
        final String localCompId,
        final String localSubId,
        final String localLocationId,
        final String remoteCompId,
        final String remoteSubId,
        final String remoteLocationId)
    {
        this.localCompId = localCompId;
        this.localSubId = localSubId;
        this.localLocationId = localLocationId;
        this.remoteCompId = remoteCompId;
        this.remoteSubId = remoteSubId;
        this.remoteLocationId = remoteLocationId;
    }

    @Override
    public String localCompId()
    {
        return localCompId;
    }

    @Override
    public String localSubId()
    {
        return localSubId;
    }

    @Override
    public String localLocationId()
    {
        return localLocationId;
    }

    @Override
    public String remoteCompId()
    {
        return remoteCompId;
    }

    @Override
    public String remoteSubId()
    {
        return remoteSubId;
    }

    @Override
    public String remoteLocationId()
    {
        return remoteLocationId;
    }

    @Override
    public String toString()
    {
        final StringBuilder builder = new StringBuilder()
            .append("CompositeKey{").append("localCompId=").append(localCompId);

        if (localSubId.length() > 0)
        {
            builder.append(", localSubId=").append(localSubId);
        }

        if (localLocationId.length() > 0)
        {
            builder.append(", localLocationId=").append(localLocationId);
        }

        builder.append(", remoteCompId=").append(remoteCompId);

        if (remoteSubId.length() > 0)
        {
            builder.append(", remoteSubId=").append(remoteSubId);
        }

        if (remoteLocationId.length() > 0)
        {
            builder.append(", remoteLocationId=").append(remoteLocationId);
        }

        return builder.append('}').toString();
    }
}
