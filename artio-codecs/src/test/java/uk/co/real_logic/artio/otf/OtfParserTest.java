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
package uk.co.real_logic.artio.otf;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.ValidationError.INVALID_CHECKSUM;
import static uk.co.real_logic.artio.ValidationError.PARSE_ERROR;
import static uk.co.real_logic.artio.util.TestMessages.*;

public class OtfParserTest
{
    public static final int NO_OFFSET = 0;

    public static final int OFFSET = 1;

    public static final int LENGTH = 16 * 1024;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[LENGTH]);
    private final OtfMessageAcceptor mockAcceptor = mock(OtfMessageAcceptor.class);
    private final LongDictionary groupToField = new LongDictionary();
    private final OtfParser parser = new OtfParser(mockAcceptor, groupToField);

    private final InOrder inOrder = inOrder(mockAcceptor);

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfMessageStart(final int offset)
    {
        putMessage(offset);

        parser.onMessage(buffer, offset, MSG_LEN);

        verify(mockAcceptor).onNext();
        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void doesNotSwallowExceptionsFromCallbacks(final int offset)
    {
        final String errorMessage = "err";
        putMessage(offset);

        when(mockAcceptor.onNext()).thenThrow(new IllegalArgumentException(errorMessage));

        try
        {
            parser.onMessage(buffer, offset, MSG_LEN);
        }
        catch (final IllegalArgumentException ex)
        {
            assertEquals(errorMessage, ex.getMessage());
            verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
            return;
        }

        fail("expected IllegalArgumentException");
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfValidMessageFields(final int offset)
    {
        putMessage(offset);

        parser.onMessage(buffer, offset, MSG_LEN);

        //8=FIX.4.2
        inOrder.verify(mockAcceptor).onField(eq(8), any(), eq(offset + 2), eq(7));
        //9=145
        inOrder.verify(mockAcceptor).onField(eq(9), any(), eq(offset + 12), eq(3));
        //35=D
        inOrder.verify(mockAcceptor).onField(eq(35), any(), eq(offset + 19), eq(1));
        //34=4
        inOrder.verify(mockAcceptor).onField(eq(34), any(), eq(offset + 24), eq(1));

        inOrder.verify(mockAcceptor, times(15)).onField(anyInt(), any(), anyInt(), anyInt());
        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void stopsParsingWhenToldTo(final int offset)
    {
        putMessage(offset);

        when(mockAcceptor.onField(eq(34), any(), eq(offset + 24), eq(1))).thenReturn(MessageControl.STOP);

        parser.onMessage(buffer, offset, MSG_LEN);

        //8=FIX.4.2
        inOrder.verify(mockAcceptor).onField(eq(8), any(), eq(offset + 2), eq(7));
        //9=145
        inOrder.verify(mockAcceptor).onField(eq(9), any(), eq(offset + 12), eq(3));
        //35=D
        inOrder.verify(mockAcceptor).onField(eq(35), any(), eq(offset + 19), eq(1));
        //34=4
        inOrder.verify(mockAcceptor).onField(eq(34), any(), eq(offset + 24), eq(1));

        inOrder.verifyNoMoreInteractions();
        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfValidMessageEnd(final int offset)
    {
        putMessage(offset);

        parser.onMessage(buffer, offset, MSG_LEN);

        verify(mockAcceptor).onComplete();
        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfInvalidChecksum(final int offset)
    {
        buffer.putBytes(offset, INVALID_CHECKSUM_MSG);

        parser.onMessage(buffer, offset, INVALID_CHECKSUM_LEN);

        verify(mockAcceptor).onError(eq(INVALID_CHECKSUM), eq((long)'D'), eq(10), any(AsciiFieldFlyweight.class));
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfInvalidMessage(final int offset)
    {
        buffer.putBytes(offset, INVALID_MESSAGE);

        parser.onMessage(buffer, offset, INVALID_LEN);

        verify(mockAcceptor).onError(eq(PARSE_ERROR), eq((long)'D'), eq(11), any(AsciiFieldFlyweight.class));
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfRepeatingGroup(final int offset)
    {
        understandsContraBrokersGroup();

        buffer.putBytes(offset, EXECUTION_REPORT);

        parser.onMessage(buffer, offset, EXECUTION_REPORT.length);

        verifyGroupHeader(382, 1);
        verifyGroupBegin(382, 1, 0);
        verifyInOrderField(375);
        verifyInOrderField(337);
        verifyInOrderField(437);
        verifyInOrderField(438);
        verifyGroupEnd(382, 1, 0);

        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfOnlyHeaderForEmptyGroup(final int offset)
    {
        understandsContraBrokersGroup();
        buffer.putBytes(offset, ZERO_REPEATING_GROUP);

        parser.onMessage(buffer, offset, ZERO_REPEATING_GROUP.length);

        verifyGroupHeader(382, 0);
        inOrder.verify(mockAcceptor, never()).onGroupBegin(anyInt(), anyInt(), anyInt());
        inOrder.verify(mockAcceptor, never()).onGroupEnd(anyInt(), anyInt(), anyInt());

        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfMultiElementRepeatingGroup(final int offset)
    {
        understandsNoOrdersGroup();

        buffer.putBytes(offset, REPEATING_GROUP);

        parser.onMessage(buffer, offset, REPEATING_GROUP.length);

        verifyGroupHeader(NO_ORDERS, 2);
        verifyNoOrdersGroup(0);
        verifyNoOrdersGroup(1);

        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void notifiesAcceptorOfNestedRepeatingGroup(final int offset)
    {
        understandsNoOrdersGroup();
        understandsNoAllocsGroup();

        buffer.putBytes(offset, NESTED_REPEATING_GROUP);

        parser.onMessage(buffer, offset, NESTED_REPEATING_GROUP.length);

        verifyGroupHeader(NO_ORDERS, 2);
        verifyNestedNoAllocsGroup(0);
        verifyNoOrdersGroup(1);

        verify(mockAcceptor, never()).onError(any(), anyLong(), anyInt(), any());
    }

    @ParameterizedTest()
    @ValueSource(ints = {NO_OFFSET, OFFSET})
    public void parsesZeroChecksumMessages(final int offset)
    {
        buffer.putBytes(offset, ZERO_CHECKSUM_MESSAGE);

        parser.onMessage(buffer, offset, ZERO_CHECKSUM_MESSAGE.length);

        verify(mockAcceptor, never()).onError(any(), anyInt(), anyInt(), any());
    }

    private void verifyGroupHeader(final int groupNumber, final int numberOfElements)
    {
        inOrder.verify(mockAcceptor, times(1)).onGroupHeader(groupNumber, numberOfElements);
    }

    private void verifyGroupBegin(final int groupNumber, final int numberOfElements, final int index)
    {
        inOrder.verify(mockAcceptor, times(1)).onGroupBegin(groupNumber, numberOfElements, index);
    }

    private void verifyGroupEnd(final int groupNumber, final int numberOfElements, final int index)
    {
        inOrder.verify(mockAcceptor, times(1)).onGroupEnd(groupNumber, numberOfElements, index);
    }

    private void verifyNoOrdersGroup(final int index)
    {
        verifyNoOrdersFields(index);
        verifyGroupEnd(NO_ORDERS, 2, index);
    }

    private void verifyNestedNoAllocsGroup(final int index)
    {
        verifyNoOrdersFields(index);

        verifyGroupHeader(NO_ALLOCS, 2);
        verifyNoAllocsGroup(0);
        verifyNoAllocsGroup(1);

        verifyGroupEnd(NO_ORDERS, 2, index);
    }

    private void verifyNoOrdersFields(final int index)
    {
        verifyGroupBegin(NO_ORDERS, 2, index);
        verifyInOrderField(11);
        verifyInOrderField(67);
        verifyInOrderField(55);
        verifyInOrderField(54);
        verifyInOrderField(38);
        verifyInOrderField(40);
    }

    private void verifyNoAllocsGroup(final int index)
    {
        verifyGroupBegin(NO_ALLOCS, 2, index);
        verifyInOrderField(79);
        verifyInOrderField(467);
        verifyInOrderField(366);
        verifyGroupEnd(NO_ALLOCS, 2, index);
    }

    private void putMessage(final int offset)
    {
        buffer.putBytes(offset, EG_MESSAGE);
    }

    private void understandsContraBrokersGroup()
    {
        groupToField.putAll(382, 337, 375, 437, 438);
    }

    private void understandsNoOrdersGroup()
    {
        groupToField.putAll(73, 11, 67, 55, 54, 38, 40, 78);
    }

    private void understandsNoAllocsGroup()
    {
        groupToField.putAll(78, 79, 467, 366);
    }

    private void verifyInOrderField(final int tag)
    {
        inOrder.verify(mockAcceptor, times(1)).onField(eq(tag), any(), anyInt(), anyInt());
    }
}
