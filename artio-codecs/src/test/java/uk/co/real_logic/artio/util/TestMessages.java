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
package uk.co.real_logic.artio.util;

import static java.nio.charset.StandardCharsets.US_ASCII;

@SuppressWarnings("LineLength")
public final class TestMessages
{
    // BUY 100 CVS MKT DAY
    public static final byte[] EG_MESSAGE = toAscii("8=FIX.4.2\0019=146\00135=D\00134=4\00149=ABC_DEFG01\001" +
        "52=20090323-15:40:29\00156=CCG\001115=XYZ\00111=NF 0542/03232009\00154=1\00138=100\00155=CVS\00140=1" +
        "\00159=0\00147=A\00160=20090323-15:40:29\00121=1\001207=N\00110=195\001");

    public static final byte[] LOGON_MESSAGE = toAscii("8=FIX.4.2\0019=64\00135=A\00134=1\00149=ABC_DEFG01" +
        "\00152=20090323-15:40:29\00156=CCG\00198=0\001108=30\00110=161\001");

    public static final int MSG_LEN = EG_MESSAGE.length;

    // garbled in the sense that 49 is split.
    public static final byte[] GARBLED_MESSAGE = toAscii("8=FIX.4.2\0019=153\00135=D\00134=4\0014garbled9=ABC_DEFG01\001" +
        "52=20090323-15:40:29\00156=CCG\001115=XYZ\00111=NF 0542/03232009\00154=1\00138=100\00155=CVS\00140=1" +
        "\00159=0\00147=A\00160=20090323-15:40:29\00121=1\001207=N\00110=146\001");

    public static final byte[] INVALID_CHECKSUM_MSG = toAscii("8=FIX.4.2\0019=133\00135=D\00134=4\00149=ABC_DEFG01\001" +
        "52=20090323-15:40:29\00156=CCG\001115=XYZ\00111=NF 0542/03232009\00155=CVS\00140=1\00159=0\00147=A" +
        "60=20090323-15:40:29\00121=1\001207=N\00110=155\001");

    public static final int INVALID_CHECKSUM_LEN = INVALID_CHECKSUM_MSG.length;

    public static final byte[] INVALID_MESSAGE = toAscii("8=FIX.4.2\0019=145\00135=D\00134=4\00149=ABC_DEFG01\001" +
        "52=\\\\\20156=CCG\001115=XYZ\00111=NF 0542/03232009\001\001\001\001\001\00154=1\00138=55140=" +
        "\00159=0\00147=A\00160=20090323-15:40:29\00121=1\001207=N\00110=172\001");

    public static final int INVALID_LEN = INVALID_MESSAGE.length;

    public static final byte[] EXECUTION_REPORT = toAscii("8=FIX.4.2\0019=378\00135=8\001128=XYZ\00134=5\00149=CCG" +
        "\00156=ABC_DEFG01\00152=20090323-" +
        "15:40:35\00155=CVS\00137=NF 0542/03232009\00111=NF 0542/03232009\00117=NF 0542/03232009" +
        "001001001\00120=0\00139=2\001150=2\00154=1\00138=100\00140=1\00159=0\00131=25.4800\00132=100\001" +
        "14=0\0016=0\001151=0\00160=20090323-15:40:30\00158=Fill\00130=N\00176=0034\001207=N\00147=A\001" +
        "9430=NX\0019483=000008\0019578=1\001382=1\001375=TOD\001337=0000\001437=100\001438=1243\001" +
        "9579=0000100001\0019426=2/2\0019433=0034\00129=1\00163=0\0019440=001001001\00110=013\001");

    public static final byte[] ZERO_REPEATING_GROUP = toAscii("8=FIX.4.2\0019=378\00135=8\001128=XYZ\00134=5\00149=CCG" +
        "\00156=ABC_DEFG01\00152=20090323-" +
        "15:40:35\00155=CVS\00137=NF 0542/03232009\00111=NF 0542/03232009\00117=NF 0542/03232009" +
        "001001001\00120=0\00139=2\001150=2\00154=1\00138=100\00140=1\00159=0\00131=25.4800\00132=100\001" +
        "14=0\0016=0\001151=0\00160=20090323-15:40:30\00158=Fill\00130=N\00176=0034\001207=N\00147=A\001" +
        "9430=NX\0019483=000008\0019578=1\001382=0\001" +
        "9579=0000100001\0019426=2/2\0019433=0034\00129=1\00163=0\0019440=001001001\00110=153\001");

    public static final byte[] REPEATING_GROUP = toAscii("8=FIX.4.2\0019=190\00135=E\00149=INST\00156=BROK\001" +
        "52=20050908-15:51:22\00134=200\00166=14\001394=1\00168=2\001" +
        "73=2\001" +
        "11=order-1\00167=1\00155=IBM\00154=2\00138=2000\00140=1\001" +
        "11=order-2\00167=2\00155=AOL\00154=2\00138=1000\00140=1\001" +
        "10=021\001");

    // See http://fixwiki.org/fixwiki/FPL:Tag_Value_Syntax#Example_of_nested_repeating_group for details
    public static final byte[] NESTED_REPEATING_GROUP = toAscii("8=FIX.4.2\0019=190\00135=E\00149=INST\00156=BROK\001" +
        "52=20050908-15:51:22\00134=200\00166=14\001394=1\00168=2\001" +
        "73=2\001" +            // NoOrders Group
        "11=order-1\00167=1\00155=IBM\00154=2\00138=2000\00140=1\001" +
        "78=2\001" +    // NoAllocs nested group
        "79=bob\001467=10\001366=4\001" +
        "79=sally\001467=11\001366=5\001" +
        "11=order-2\00167=2\00155=AOL\00154=2\00138=1000\00140=1\001" +
        "10=076\001");

    public static final byte[] INVALID_LENGTH_MESSAGE = toAscii(
        "8=FIX.4.4\0019=5\00135=A\00134=1\00149=TW\00152=20150604-12:46:54\00156=ISLD\00198=0\00110=000\001");

    public static final byte[] ZERO_CHECKSUM_MESSAGE = toAscii(
        "8=FIX.4.4\0019=0067\00135=0\00149=acceptor\00156=initiator\00134=2" +
        "\00152=20160415-12:50:23.294\001112=hi\00110=000\001");

    public static final int NO_ORDERS = 73;
    public static final int NO_ALLOCS = 78;

    private static byte[] toAscii(final String str)
    {
        return str.getBytes(US_ASCII);
    }
}
