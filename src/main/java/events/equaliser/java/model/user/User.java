package events.equaliser.java.model.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.auth.Credentials;
import events.equaliser.java.image.ResizeSpecification;
import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.image.Image;
import events.equaliser.java.model.image.ImageSize;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.Random;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a registered Equaliser user.
 */
public class User extends PublicUser {

    private static final Logger logger = LoggerFactory.getLogger(User.class);

    private static final int TOKEN_BYTES = 32;
    private static final int MINIMUM_PROFILE_PHOTO_DIMENSION = 500;

    /**
     * The user's unique identifier.
     */
    private final int id;

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

    private final int imageId;

    private Image image;

    @JsonIgnore
    public int getId() {
        return id;
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

    public Image getImage() {
        return image;
    }

    /**
     * Initialise a new user.
     * @param id The user's unique identifier.
     * @param username The user's globally unique username.
     * @param forename The user's forename.
     * @param surname The user's surname.
     * @param email The user's email address.
     * @param country The user's home country.
     * @param areaCode The user's area code, e.g. "020", or "01372".
     * @param subscriberNumber The subscriber portion of the user's phone number, e.g. "842336".
     * @param token The user's identification token.
     * @param imageId The user's image pointer.
     */
    public User(int id, String username, String forename, String surname, String email, Country country,
                String areaCode, String subscriberNumber, byte[] token, int imageId) {
        super(username, forename, surname);
        this.id = id;
        this.email = email;
        this.country = country;
        this.areaCode = areaCode;
        this.subscriberNumber = subscriberNumber;
        this.token = token;
        this.imageId = imageId;
    }

    public User(int id, String username, String forename, String surname, String email, Country country,
                String areaCode, String subscriberNumber, byte[] token, Image image) {
        super(username, forename, surname);
        this.id = id;
        this.email = email;
        this.country = country;
        this.areaCode = areaCode;
        this.subscriberNumber = subscriberNumber;
        this.token = token;
        this.imageId = image.getId();
        this.image = image;
    }

    /**
     * Get the user's phone number.
     *
     * @return The phone number.
     */
    @JsonProperty("phone")
    public String getPhoneNumber() {
        return String.format("+%s%s%s", getCountry().getCallingCode(), getAreaCode(), getSubscriberNumber());
    }

    @JsonProperty("token")
    public String getTokenHex() {
        return Hex.binToHex(getToken());
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
     * Turn a JSON object into a user.
     *
     * @param json The JSON object with correct keys.
     * @return The User representation of the object.
     */
    public static User fromJsonObject(JsonObject json) {
        return new User(
                json.getInteger("UserID"),
                json.getString("UserUsername"),
                json.getString("UserForename"),
                json.getString("UserSurname"),
                json.getString("UserEmail"),
                Country.fromJsonObject(json),
                json.getString("UserAreaCode"),
                json.getString("UserSubscriberNumber"),
                json.getBinary("UserToken"),
                json.getInteger("UserImageID"));
    }

    public static void fromJsonObject(JsonObject json, SQLConnection connection, Handler<AsyncResult<User>> handler) {
        User user = fromJsonObject(json);
        Image.retrieveFromId(user.imageId, connection, res -> {
            if (res.failed()) {
                handler.handle(Future.failedFuture(res.cause()));
                return;
            }

            user.image = res.result();
            handler.handle(Future.succeededFuture(user));
        });
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
                    "Users.ImageID AS UserImageID, " +
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
                            retrieveProfilePicture(row, connection, result);
                        }
                    }
                    else {
                        result.handle(Future.failedFuture(userResult.cause()));
                    }
                });
    }

    public static void retrieveProfilePicture(JsonObject row,
                                              SQLConnection connection,
                                              Handler<AsyncResult<User>> handler) {
        User.fromJsonObject(row, connection, res -> {
            if (res.failed()) {
                handler.handle(Future.failedFuture(res.cause()));
                return;
            }

            handler.handle(Future.succeededFuture(res.result()));
        });
    }

    public static void register(String username,
                                Country country,
                                String forename, String surname,
                                String email,
                                String areaCode, String subscriberNumber,
                                String password,
                                BufferedImage image,
                                SQLConnection connection,
                                Handler<AsyncResult<User>> handler) {
        // TODO this all needs to be wrapped in a big transaction
        Vertx.currentContext().executeBlocking(code -> {
            try {
                // TODO move somewhere where this chunk can be reused
                List<BufferedImage> toWrite = ResizeSpecification.resize(image, ResizeSpecification.PROFILE_PHOTO);
                logger.debug("Need to create {} versions of profile picture", toWrite.size());
                List<ImageSize> written = new ArrayList<>();
                for (BufferedImage size : toWrite) {
                    File tempFile = File.createTempFile("profile-picture", ".jpg");
                    ImageIO.write(size, "JPG", tempFile);
                    ImageSize rendered = new ImageSize(tempFile, size);
                    rendered.moveToStorage(tempFile);
                    logger.debug("Finished {}", rendered);
                    written.add(rendered);
                }
                code.complete(written);
            } catch (IOException | NoSuchAlgorithmException e) {
                code.fail(e);
            }
        }, result -> {
            if (result.failed()) {
                handler.handle(Future.failedFuture(result.cause()));
                return;
            }
            @SuppressWarnings("unchecked")
            List<ImageSize> sizes = (List<ImageSize>)result.result();
            Image.insert(sizes, connection, imageRes -> {
                if (imageRes.succeeded()) {
                    Image photo = imageRes.result();
                    byte[] token = Random.getBytes(TOKEN_BYTES);
                    String hashedPassword = Credentials.hash(password);
                    JsonArray params = new JsonArray()
                            .add(username)
                            .add(country.getId())
                            .add(forename).add(surname)
                            .add(email)
                            .add(areaCode).add(subscriberNumber)
                            .add(hashedPassword)
                            .add(token)
                            .add(photo.getId());
                    connection.updateWithParams(
                            "INSERT INTO Users (Username, CountryID, Forename, Surname, Email, AreaCode, SubscriberNumber, " +
                                    "Password, Token, ImageID) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FROM_BASE64(?), ?);",
                            params, res -> {
                                if (res.succeeded()) {
                                    UpdateResult userResult = res.result();
                                    int id = userResult.getKeys().getInteger(0);
                                    User user = new User(id, username, forename, surname, email, country, areaCode,
                                            subscriberNumber, token, photo);
                                    handler.handle(Future.succeededFuture(user));
                                }
                                else {
                                    handler.handle(Future.failedFuture(res.cause()));
                                }
                            });
                }
                else {
                    handler.handle(Future.failedFuture(imageRes.cause()));
                }
            });
        });
    }

    public static void validateProfilePhoto(BufferedImage image) {
        if (image.getHeight() < MINIMUM_PROFILE_PHOTO_DIMENSION ||
                image.getWidth() < MINIMUM_PROFILE_PHOTO_DIMENSION) {
            throw new IllegalArgumentException(
                    String.format("Image must be at least %dx%dpx",
                            MINIMUM_PROFILE_PHOTO_DIMENSION,
                            MINIMUM_PROFILE_PHOTO_DIMENSION));
        }

        // we don't really care what the type of image is; we can convert it to JPG
    }
}
