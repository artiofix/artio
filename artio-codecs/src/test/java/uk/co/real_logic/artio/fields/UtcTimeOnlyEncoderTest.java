package uk.co.real_logic.artio.fields;

import org.junit.jupiter.api.Test;

class UtcTimeOnlyEncoderTest
{

    @Test
    void encode()
    {
        final UtcTimeOnlyEncoder encoder = new UtcTimeOnlyEncoder();
        encoder.encode(1000000, new byte[100]);
    }
}
