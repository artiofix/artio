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
package uk.co.real_logic.artio.sbe_util;

import org.agrona.DirectBuffer;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.IrDecoder;
import uk.co.real_logic.sbe.ir.Token;
import uk.co.real_logic.sbe.otf.AbstractTokenListener;
import uk.co.real_logic.sbe.otf.OtfMessageDecoder;

import java.util.List;

public class IdExtractor extends AbstractTokenListener
{
    public static final int NO_LIBRARY_ID = 0;
    public static final int NO_CORRELATION_ID = 0;

    private final Ir ir;

    private int libraryId;
    private long correlationId;

    public IdExtractor()
    {
        ir = new IrDecoder(MessageSchemaIr.SCHEMA_BUFFER).decode();
    }

    public int decode(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int actingVersion,
        final int templateId)
    {
        final List<Token> tokens = ir.getMessage(templateId);

        libraryId = NO_LIBRARY_ID;
        correlationId = NO_CORRELATION_ID;

        return OtfMessageDecoder.decode(
            buffer,
            offset,
            actingVersion,
            blockLength,
            tokens,
            this);
    }

    @Override
    public void onEncoding(
        final Token fieldToken,
        final DirectBuffer buffer,
        final int bufferIndex,
        final Token typeToken,
        final int actingVersion)
    {
        switch (fieldToken.name())
        {
            case "libraryId":
                libraryId = buffer.getInt(bufferIndex);
                break;

            case "correlationId":
            case "connectCorrelationId":
                correlationId = buffer.getLong(bufferIndex);
                break;
        }
    }

    public int libraryId()
    {
        return libraryId;
    }

    public long correlationId()
    {
        return correlationId;
    }
}
