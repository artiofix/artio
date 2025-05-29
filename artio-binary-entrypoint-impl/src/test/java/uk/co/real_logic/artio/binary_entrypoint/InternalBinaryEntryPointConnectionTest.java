/*
 * Copyright 2021 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.binary_entrypoint;

import b3.entrypoint.fixp.sbe.CancelOnDisconnectType;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.junit.Test;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.fixp.FixPMessageDissector;
import uk.co.real_logic.artio.library.FixPSessionOwner;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import static b3.entrypoint.fixp.sbe.RetransmitRejectCode.RETRANSMIT_IN_PROGRESS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;

public class InternalBinaryEntryPointConnectionTest
{
    private static final long CONNECTION_ID = 1;
    private static final int LIBRARY_ID = 2;
    private static final long SESSION_ID = 3;
    private static final long ENTERING_FIRM = 4;
    public static final int SESSION_VER_ID = 1;

    @Test
    public void shouldRejectMultipleConcurrentResendRequests()
    {
        final BinaryEntryPointProxy proxy = mock(BinaryEntryPointProxy.class);
        final EpochNanoClock clock = new OffsetEpochNanoClock();
        final BinaryEntryPointContext context = new BinaryEntryPointContext(
            SESSION_ID, SESSION_VER_ID, clock.nanoTime(), ENTERING_FIRM, false, "",
            "", "", "");
        final GatewayPublication inboundPublication = mock(GatewayPublication.class);

        final BinaryEntryPointProtocol protocol = new BinaryEntryPointProtocol();
        final InternalBinaryEntryPointConnection connection = new InternalBinaryEntryPointConnection(
            CONNECTION_ID,
            mock(GatewayPublication.class),
            inboundPublication,
            LIBRARY_ID,
            mock(FixPSessionOwner.class),
            UNK_SESSION,
            UNK_SESSION,
            0,
            new CommonConfiguration(),
            context,
            proxy,
            mock(FixPMessageDissector.class),
            protocol);
        connection.handler(mock(FixPConnectionHandler.class));

        connection.onEstablish(
            SESSION_ID, SESSION_VER_ID, clock.nanoTime(), 3_000, 1, CancelOnDisconnectType.NULL_VAL, 0);
        connection.nextRecvSeqNo(5);
        connection.nextSentSeqNo(5);

        when(inboundPublication.saveValidResendRequest(
            eq(SESSION_ID),
            eq(CONNECTION_ID),
            anyLong(),
            anyLong(),
            eq(SESSION_VER_ID),
            eq(0),
            any(),
            anyInt(),
            anyInt())).thenReturn(1000L);

        long requestTimestampInNs = clock.nanoTime();
        onRetransmitRequest(connection, requestTimestampInNs, 2);
        successfulRetransmit(proxy, requestTimestampInNs, 2L);

        requestTimestampInNs++;
        onRetransmitRequest(connection, requestTimestampInNs, 3);
        verify(proxy, never()).sendRetransmissionWithSequence(
            eq(3L), eq(1L), anyLong(), anyLong(), anyLong());
        verify(proxy).sendRetransmitReject(eq(RETRANSMIT_IN_PROGRESS), anyLong(), eq(requestTimestampInNs));
        reset(proxy);

        connection.onReplayComplete();

        requestTimestampInNs++;
        onRetransmitRequest(connection, requestTimestampInNs, 4);
        successfulRetransmit(proxy, requestTimestampInNs, 4L);
    }

    @Test
    public void shouldSendNotAppliedMessageUsingBinaryProxy()
    {
        final BinaryEntryPointProxy proxy = mock(BinaryEntryPointProxy.class);
        final EpochNanoClock clock = new OffsetEpochNanoClock();
        final BinaryEntryPointContext context = new BinaryEntryPointContext(SESSION_ID, SESSION_VER_ID,
            clock.nanoTime(), ENTERING_FIRM, false, "", "", "", "");
        final GatewayPublication inboundPublication = mock(GatewayPublication.class);
        final BinaryEntryPointProtocol protocol = new BinaryEntryPointProtocol();

        final InternalBinaryEntryPointConnection connection = new InternalBinaryEntryPointConnection(
            CONNECTION_ID,
            mock(GatewayPublication.class),
            inboundPublication,
            LIBRARY_ID,
            mock(FixPSessionOwner.class),
            UNK_SESSION,
            UNK_SESSION,
            0,
            new CommonConfiguration(),
            context,
            proxy,
            mock(FixPMessageDissector.class),
            protocol);

        connection.trySendNotApplied(0, 5);
        verify(proxy, times(1)).sendNotApplied(eq(0L), eq(5L), anyLong());
    }

    private void successfulRetransmit(
        final BinaryEntryPointProxy proxy, final long requestTimestampInNs, final long fromSeqNo)
    {
        verify(proxy).sendRetransmissionWithSequence(
            eq(fromSeqNo), eq(1L), anyLong(), eq(requestTimestampInNs), anyLong());
        verify(proxy, never()).sendRetransmitReject(any(), anyLong(), anyLong());
        reset(proxy);
    }

    private void onRetransmitRequest(
        final InternalBinaryEntryPointConnection connection, final long requestTimestampInNs, final int fromSeqNo)
    {
        connection.onRetransmitRequest(SESSION_ID, requestTimestampInNs, fromSeqNo, 1);
    }

}
