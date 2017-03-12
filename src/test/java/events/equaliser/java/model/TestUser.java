package events.equaliser.java.model;

import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.geography.TestCountry;
import events.equaliser.java.model.user.User;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

public class TestUser {

    private static final int ID = 1;
    private static final String USERNAME = "ttest";
    private static final String FORENAME = "Terry";
    private static final String SURNAME = "Test";
    private static final String EMAIL = "terry@test.com";
    private static final Country COUNTRY = TestCountry.COUNTRY;
    private static final String AREA_CODE = "020";
    private static final String SUBSCRIBER_NUMBER = "83983687";
    private static final byte[] TOKEN = new byte[16];
    public static final User USER = new User(ID, USERNAME, FORENAME, SURNAME, EMAIL, COUNTRY, AREA_CODE,
            SUBSCRIBER_NUMBER, TOKEN);

    @Test
    public void testId() {
        Assert.assertEquals(USER.getId(), ID);
    }

    @Test
    public void testUsername() {
        Assert.assertEquals(USER.getUsername(), USERNAME);
    }

    @Test
    public void testForename() {
        Assert.assertEquals(USER.getForename(), FORENAME);
    }

    @Test
    public void testSurname() {
        Assert.assertEquals(USER.getSurname(), SURNAME);
    }

    @Test
    public void testEmail() {
        Assert.assertEquals(USER.getEmail(), EMAIL);
    }

    @Test
    public void testCountry() {
        Assert.assertEquals(USER.getCountry(), COUNTRY);
    }

    @Test
    public void testAreaCode() {
        Assert.assertEquals(USER.getAreaCode(), AREA_CODE);
    }

    @Test
    public void testSubscriberNumber() {
        Assert.assertEquals(USER.getSubscriberNumber(), SUBSCRIBER_NUMBER);
    }

    @Test
    public void testToken() {
        Assert.assertEquals(USER.getTokenAsHex(), new String(new char[TOKEN.length * 2]).replace("\0", "0"));
    }
}
