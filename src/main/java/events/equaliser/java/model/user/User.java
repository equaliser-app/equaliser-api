package events.equaliser.java.model.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.model.geography.Country;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.util.UUID;

/**
 * Represents a registered Equaliser user.
 */
public class User {

    /**
     * The user's unique identifier.
     */
    private final int id;

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

    /**
     * The user's identification token.
     */
    private final byte[] token;

    public int getId() {
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

    @JsonIgnore
    public String getAreaCode() {
        return areaCode;
    }

    @JsonIgnore
    public String getSubscriberNumber() {
        return subscriberNumber;
    }

    @JsonIgnore
    private byte[] getToken() {
        return token;
    }

    /**
     * Initialise a new user.
     *
     * @param id The user's unique identifier.
     * @param username The user's globally unique username.
     * @param forename The user's forename.
     * @param surname The user's surname.
     * @param email The user's email address.
     * @param country The user's home country.
     * @param areaCode The user's area code, e.g. "020", or "01372".
     * @param subscriberNumber The subscriber portion of the user's phone number, e.g. "842336".
     * @param token The user's identification token.
     */
    public User(int id, String username, String forename, String surname, String email, Country country,
                String areaCode, String subscriberNumber, byte[] token) {
        this.id = id;
        this.username = username;
        this.forename = forename;
        this.surname = surname;
        this.email = email;
        this.country = country;
        this.areaCode = areaCode;
        this.subscriberNumber = subscriberNumber;
        this.token = token;
    }

    /**
     * Get the user's phone number.
     *
     * @return The phone number.
     */
    @JsonProperty("phone")
    public String getPhoneNumber() {
        // TODO format more nicely
        return String.format("+%s%s%s", getCountry().getCallingCode(), getAreaCode(), getSubscriberNumber());
    }

    @JsonProperty("token")
    public String getTokenAsHex() {
        StringBuilder builder = new StringBuilder();
        for (byte b : getToken()) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /**
     * Retrieve the recipient line for this user, suitable for inclusion in an email.
     *
     * @return The recipient line containing the user's forename, surname and email address.
     */
    @JsonIgnore
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

    /**
     * Turn a JSON object into a user.
     *
     * @param json The JSON object with correct keys.
     * @return The User representation of the object.
     */
    private static User fromJsonObject(JsonObject json) {
        return new User(
                json.getInteger("UserID"),
                json.getString("UserUsername"),
                json.getString("UserForename"),
                json.getString("UserSurname"),
                json.getString("UserEmail"),
                Country.fromJsonObject(json),
                json.getString("UserAreaCode"),
                json.getString("UserSubscriberNumber"),
                json.getBinary("UserToken"));
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<User>> result) {
        JsonArray params = new JsonArray().add(id);
        connection.queryWithParams(
                "SELECT " +
                    "Users.UserID, " +
                    "Users.Username AS UserUsername, " +
                    "Users.Forename AS UserForename, " +
                    "Users.Surname AS UserSurname, " +
                    "Users.Email AS UserEmail, " +
                    "Users.AreaCode AS UserAreaCode, " +
                    "Users.SubscriberNumber AS UserSubscriberNumber, " +
                    "Users.Token AS UserToken, " +
                    "Countries.CountryID, " +
                    "Countries.Name AS CountryName, " +
                    "Countries.Abbreviation AS CountryAbbreviation, " +
                    "Countries.CallingCode AS CountryCallingCode " +
                "FROM Users " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                "WHERE Users.UserID = ?;",
                params, userResult -> {
                    if (userResult.succeeded()) {
                        ResultSet resultSet = userResult.result();
                        if (resultSet.getNumRows() == 0) {
                            result.handle(Future.failedFuture("No user found with id " + id));
                        }
                        else {
                            JsonObject row = resultSet.getRows().get(0);
                            User user = User.fromJsonObject(row);
                            result.handle(Future.succeededFuture(user));
                        }
                    }
                    else {
                        result.handle(Future.failedFuture(userResult.cause()));
                    }
                });
    }
}
