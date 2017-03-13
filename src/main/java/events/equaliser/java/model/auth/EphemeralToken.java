package events.equaliser.java.model.auth;

import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Random;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.time.OffsetDateTime;


public class EphemeralToken {

    private static final int TOKEN_BYTES = 32;
    private static final int VALIDITY_SECONDS = 60 * 10;

    private final User user;
    private final byte[] token;
    private final OffsetDateTime expires;

    public User getUser() {
        return user;
    }

    public byte[] getToken() {
        return token;
    }

    public OffsetDateTime getExpires() {
        return expires;
    }

    public EphemeralToken(User user, byte[] token, OffsetDateTime expires) {
        this.user = user;
        this.token = token;
        this.expires = expires;
    }

    private EphemeralToken(User user) {
        this(user, Random.getBytes(TOKEN_BYTES), OffsetDateTime.now().plusSeconds(VALIDITY_SECONDS));
    }

    public static void generate(User user,
                                SQLConnection connection,
                                Handler<AsyncResult<EphemeralToken>> handler) {
        EphemeralToken token = new EphemeralToken(user);
        JsonArray params = new JsonArray()
                .add(token.getUser().getId())
                .add(token.getToken())
                .add(Time.toSql(token.getExpires()));
        connection.updateWithParams(
                "INSERT INTO EphemeralTokens (UserID, Token, Expires) VALUES (?, FROM_BASE64(?), ?);",
                params, res -> {
                    if (res.succeeded()) {
                        handler.handle(Future.succeededFuture(token));
                    }
                    else {
                        handler.handle(Future.failedFuture(res.cause()));
                    }
                });
    }

    public static void validate(byte[] token,
                                SQLConnection connection,
                                Handler<AsyncResult<User>> handler) {
        JsonArray params = new JsonArray()
                .add(token);
        connection.queryWithParams(
                "SELECT UserID " +
                "FROM EphemeralTokens " +
                "WHERE Token = FROM_BASE64(?) AND NOW() <= Expires;",
                params, tokenResult -> {
                    if (tokenResult.succeeded()) {
                        ResultSet results = tokenResult.result();
                        if (results.getNumRows() == 0) {
                            handler.handle(Future.failedFuture("Invalid token"));
                        }
                        else {
                            JsonObject row = results.getRows().get(0);
                            int userId = row.getInteger("UserID");
                            User.retrieveFromId(userId, connection, userResult -> {
                                        if (userResult.succeeded()) {
                                            handler.handle(Future.succeededFuture(userResult.result()));
                                        }
                                        else {
                                            // should never happen due to foreign key
                                            handler.handle(Future.failedFuture(userResult.cause()));
                                        }
                                    });
                        }
                    }
                    else {
                        handler.handle(Future.failedFuture(tokenResult.cause()));
                    }
                });
    }

    // TODO remove old tokens, both when new one is created, or and when one is validated
}
