package uk.co.real_logic.artio.dictionary.generation;

import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.FloatOverflowHandlerSample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.co.real_logic.artio.util.Reflection.get;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SharedCodecsWithCustomOverflowHandlerTest extends AbstractSharedCodecsTest
{

    @BeforeAll
    public static void generate() throws Exception
    {
        AbstractSharedCodecsTest.generate(FloatOverflowHandlerSample.class.getName());
    }

    @Test
    public void shouldForwardOverflowToCustomHandler() throws Exception
    {
        final Decoder decoder = executionReportDecoder1();
        WRAPPER.decode(decoder, ALL_FIELDS_WITH_OVERFLOW_MSG);

        assertTrue(decoder.validate());
        assertEquals(new DecimalFloat(999, 1), get(decoder, "combinableType"), decoder.toString());
    }
}
