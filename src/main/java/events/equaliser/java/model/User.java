package events.equaliser.java.model;

import events.equaliser.java.model.geography.Country;

import java.util.UUID;

/**
 * Represents a registered Equaliser user.
 */
public class User {

    /**
     * The user's globally unique identifier.
     */
    private final UUID id;

    /**
     * The user's globally unique username.
     */
    private final String username;

    /**
     * The user's forename.
     */
    private final String forename;

    /**
     * The user's surname.
     */
    private final String surname;

    /**
     * The user's email address.
     */
    private final String email;

    /**
     * The user's home country.
     */
    private final Country country;

    /**
     * The user's area code, e.g. "020", or "01372".
     */
    private final String areaCode;

    /**
     * The subscriber portion of the user's phone number, e.g. "842336".
     */
    private final String subscriberNumber;

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getForename() {
        return forename;
    }

    public String getSurname() {
        return surname;
    }

    public String getEmail() {
        return email;
    }

    public Country getCountry() {
        return country;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public String getSubscriberNumber() {
        return subscriberNumber;
    }

    /**
     * Initialise a new user.
     *
     * @param id The user's globally unique identifier.
     * @param username The user's globally unique username.
     * @param forename The user's forename.
     * @param surname The user's surname.
     * @param email The user's email address.
     * @param country The user's home country.
     * @param areaCode The user's area code, e.g. "020", or "01372".
     * @param subscriberNumber The subscriber portion of the user's phone number, e.g. "842336".
     */
    public User(UUID id, String username, String forename, String surname, String email, Country country,
                String areaCode, String subscriberNumber) {
        this.id = id;
        this.username = username;
        this.forename = forename;
        this.surname = surname;
        this.email = email;
        this.country = country;
        this.areaCode = areaCode;
        this.subscriberNumber = subscriberNumber;
    }

    /**
     * Get the user's phone number.
     *
     * @return The phone number.
     */
    public String getPhoneNumber() {
        // TODO format more nicely
        return String.format("(%s) %s%s", getCountry().getCallingCode(), getAreaCode(), getSubscriberNumber());
    }

    /**
     * Retrieve the recipient line for this user, suitable for inclusion in an email.
     *
     * @return The recipient line containing the user's forename, surname and email address.
     */
    public String getEmailRecipient() {
        return String.format("%s %s <%s>", getForename(), getSurname(), getEmail());
    }

    /**
     * Get a string representation of this user.
     *
     * @return The user as a string.
     */
    public String toString() {
        return String.format("User(%s)", getUsername());
    }
}
