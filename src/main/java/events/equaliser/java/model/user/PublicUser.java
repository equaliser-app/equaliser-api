package events.equaliser.java.model.user;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents user information that may be publicly displayed.
 * This should not contain any sensitive information, including GUID.
 */
public class PublicUser {

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

    public String getUsername() {
        return username;
    }

    public String getForename() {
        return forename;
    }

    public String getSurname() {
        return surname;
    }

    PublicUser(String username, String forename, String surname) {
        this.username = username;
        this.forename = forename;
        this.surname = surname;
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
     * @return The PublicUser representation of the object.
     */
    public static PublicUser fromJsonObject(JsonObject json) {
        return new PublicUser(
                json.getString("UserUsername"),
                json.getString("UserForename"),
                json.getString("UserSurname"));
    }

    public static void searchByUsername(String query,
                                        int limit,
                                        SQLConnection connection,
                                        Handler<AsyncResult<List<PublicUser>>> handler) {
        JsonArray params = new JsonArray().add(query + "%").add(limit);
        connection.queryWithParams(
                "SELECT " +
                    "Username AS UserUsername, " +
                    "Forename AS UserForename, " +
                    "Surname AS UserSurname " +
                "FROM Users " +
                "WHERE Username LIKE ? " +
                "ORDER BY Username ASC " +
                "LIMIT ?;",
                params, queryRes -> {
                    if (queryRes.succeeded()) {
                        ResultSet results = queryRes.result();
                        List<PublicUser> users = results.getRows()
                                .stream()
                                .map(PublicUser::fromJsonObject)
                                .collect(Collectors.toList());
                        handler.handle(Future.succeededFuture(users));
                    }
                    else {
                        handler.handle(Future.failedFuture(queryRes.cause()));
                    }
                });
    }
}
