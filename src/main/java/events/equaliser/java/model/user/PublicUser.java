package events.equaliser.java.model.user;

/**
 * Represents user information that may be publicly displayed.
 * This should not contain any sensitive information, including GUID.
 */
public class PublicUser {

    private final String username;
    private final String forename;
    private final String surname;

    public PublicUser(String username, String forename, String surname) {
        this.username = username;
        this.forename = forename;
        this.surname = surname;
    }
}
