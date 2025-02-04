package uk.co.real_logic.artio.dictionary.generation;

import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.fields.RejectReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SharedCodecsWithDefaultOverflowHandlerTest extends AbstractSharedCodecsTest
{

    @BeforeAll
    public static void generate() throws Exception
    {
        AbstractSharedCodecsTest.generate(null);
    }

    @Test
    public void shouldForwardOverflowToDefaultHandler() throws Exception
    {
        final Decoder decoder = executionReportDecoder1();
        WRAPPER.decode(decoder, ALL_FIELDS_WITH_OVERFLOW_MSG);

        assertFalse(decoder.validate());
        assertEquals(RejectReason.INCORRECT_DATA_FORMAT_FOR_VALUE, RejectReason.decode(decoder.rejectReason()));
    }
}
