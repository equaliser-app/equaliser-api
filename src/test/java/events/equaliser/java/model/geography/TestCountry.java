package events.equaliser.java.model.geography;

import org.junit.Assert;
import org.junit.Test;

public class TestCountry {

    private static final int ID = 1;
    private static final String NAME = "United Kingdom";
    private static final String ABBREVIATION = "UK";
    private static final String CALLING_CODE = "44";
    public static final Country COUNTRY = new Country(ID, NAME, ABBREVIATION, CALLING_CODE);

    @Test
    public void testId() {
        Assert.assertEquals(COUNTRY.getId(), ID);
    }

    @Test
    public void testName() {
        Assert.assertEquals(COUNTRY.getName(), NAME);
    }

    @Test
    public void testAbbreviation() {
        Assert.assertEquals(COUNTRY.getAbbreviation(), ABBREVIATION);
    }

    @Test
    public void testCallingCode() {
        Assert.assertEquals(COUNTRY.getCallingCode(), CALLING_CODE);
    }

    @Test
    public void testToString() {
        Assert.assertEquals(COUNTRY.toString(), String.format("Country(%s, %s)", NAME, ABBREVIATION));
    }
}
