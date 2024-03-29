package events.equaliser.java.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.Random;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;

import java.time.OffsetDateTime;

/**
 * Represents an authenticated user session.
 * N.B. This does *not* use cookies; clients are responsible for passing a valid token in the "Authorization" header to
 *      endpoints requiring authentication.
 */
public class Session {

    /**
     * The size of session tokens in bytes.
     */
    private static final int SESSION_TOKEN_LENGTH = 64;

    /**
     * The unique session identifier.
     */
    private final int id;

    /**
     * The user associated with this session.
     */
    private final User user;

    /**
     * When this session was started.
     */
    private final OffsetDateTime started;

    /**
     * The session token, of length SESSION_TOKEN_LENGTH.
     */
    private final byte[] token;

    @JsonIgnore
    public int getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OffsetDateTime getStarted() {
        return started;
    }

    @JsonIgnore
    private byte[] getToken() {
        return token;
    }

    @JsonProperty("token")
    public String getTokenHex() {
        return Hex.binToHex(getToken());
    }

    /**
     * Initialise a new session.
     * @param id The unique session identifier.
     * @param user The user associated with this session.
     * @param started When this session was started.
     * @param token The session token, of length SESSION_TOKEN_LENGTH.
     */
    private Session(int id, User user, OffsetDateTime started, byte[] token) {
        this.id = id;
        this.user = user;
        this.started = started;
        this.token = token;
    }

    public String toString() {
        return String.format("Session(%s)", getUser());
    }

    /**
     * Turn a JSON object into a session.
     *
     * @param json The JSON object with correct keys.
     * @return The Session representation of the object.
     */
    static Session fromJsonObject(JsonObject json) {
        return new Session(
                json.getInteger("SessionID"),
                User.fromJsonObject(json),
                Time.parseOffsetDateTime(json.getString("SessionStarted")),
                json.getBinary("SessionToken"));
    }

    /**
     * Create a new session for a user.
     *
     * @param user The user to associate with the new session.
     * @param connection An open database connection.
     * @param handler A result handler that will receive the created session on success.
     */
    public static void create(User user,
                              SQLConnection connection,
                              Handler<AsyncResult<Session>> handler) {
        OffsetDateTime started = OffsetDateTime.now();
        byte[] token = Random.getBytes(SESSION_TOKEN_LENGTH);
        JsonArray params = new JsonArray()
                .add(user.getId())
                .add(Time.toSql(started))
                .add(token);
        connection.updateWithParams(
                "INSERT INTO Sessions (UserID, Started, Token) " +
                "VALUES (?, ?, FROM_BASE64(?));",
                params, res -> {
                    if (res.succeeded()) {
                        UpdateResult result = res.result();
                        int sessionId = result.getKeys().getInteger(0);
                        Session session = new Session(sessionId, user, started, token);
                        handler.handle(Future.succeededFuture(session));
                    }
                    else {
                        handler.handle(Future.failedFuture(res.cause()));
                    }
                });

    }

    /**
     * Retrieve a session object by its unique identifier.
     *
     * @param token The session identifier.
     * @param connection An open database connection.
     * @param handler A result handler that will receive the created session on success.
     */
    public static void retrieveByToken(byte[] token,
                                       SQLConnection connection,
                                       Handler<AsyncResult<Session>> handler) {
        JsonArray params = new JsonArray()
                .add(token);
        // we indulge in a little optimisation here to fetch the user and country in one as this runs on most requests
        connection.queryWithParams(
                "SELECT " +
                    "Sessions.SessionID, " +
                    "Sessions.Started AS SessionStarted, " +
                    "Sessions.Token AS SessionToken, " +
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
                "FROM Sessions " +
                    "INNER JOIN Users " +
                        "ON Users.UserID = Sessions.UserID " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                "WHERE Sessions.Token = FROM_BASE64(?) AND Sessions.IsInvalidated = false;",
                params, sessionResult -> {
                            if (sessionResult.succeeded()) {
                                ResultSet results = sessionResult.result();
                                if (results.getNumRows() == 0) {
                                    handler.handle(Future.failedFuture("Invalid token"));
                                }
                                else {
                                    JsonObject row = results.getRows().get(0);
                                    Session session = Session.fromJsonObject(row);
                                    handler.handle(Future.succeededFuture(session));
                                }
                            }
                            else {
                                handler.handle(Future.failedFuture(sessionResult.cause()));
                            }
                        });
    }
}
