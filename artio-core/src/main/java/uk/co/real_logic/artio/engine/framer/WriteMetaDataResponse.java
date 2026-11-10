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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.messages.MetaDataStatus;

public class WriteMetaDataResponse implements AdminCommand
{
    private final int libraryId;
    private final long correlationId;
    private final MetaDataStatus status;

    public WriteMetaDataResponse(final int libraryId, final long correlationId, final MetaDataStatus status)
    {
        this.libraryId = libraryId;
        this.correlationId = correlationId;
        this.status = status;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onWriteMetaDataResponse(this);
    }

    public int libraryId()
    {
        return libraryId;
    }

    public long correlationId()
    {
        return correlationId;
    }

    public MetaDataStatus status()
    {
        return status;
    }
}
