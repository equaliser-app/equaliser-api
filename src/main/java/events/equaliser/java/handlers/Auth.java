package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import events.equaliser.java.auth.Credentials;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.EphemeralToken;
import events.equaliser.java.model.auth.SecurityEvent;
import events.equaliser.java.model.auth.SecurityEventType;
import events.equaliser.java.model.auth.TwoFactorToken;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request handlers related to authentication and login.
 */
public class Auth {

    private static final Logger logger = LoggerFactory.getLogger(Auth.class);

    /**
     * The first-stage authentication endpoint, validating a username/email and password pair.
     *
     * @param context The routing context.
     * @param connection A database connection.
     * @param handler The result.
     */
    public static void postAuthFirst(RoutingContext context,
                                     SQLConnection connection,
                                     Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        String username = request.getFormAttribute("username");  // or email
        if (username == null) {
            handler.handle(Future.failedFuture("'username' param missing"));
            return;
        }
        String password = request.getFormAttribute("password");
        if (password == null) {
            handler.handle(Future.failedFuture("'password' param missing"));
            return;
        }
        Credentials.validate(username, password, connection,
                credentials -> TwoFactorToken.initiateTwoFactor(connection, credentials, handler));
    }

    /**
     * The second-stage authentication endpoint, validating a 2FA code.
     *
     * @param context The routing context.
     * @param connection A database connection.
     * @param handler The result.
     */
    public static void postAuthSecond(RoutingContext context,
                                      SQLConnection connection,
                                      Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        try {
            byte[] token = Hex.hexToBin(request.getFormAttribute("token"));
            if (token == null) {
                handler.handle(Future.failedFuture("'token' param missing"));
                return;
            }
            String code = request.getFormAttribute("code");
            if (code == null) {
                handler.handle(Future.failedFuture("'code' param missing"));
                return;
            }
            TwoFactorToken.validate(token, code, connection,
                    (result) -> validateToken(context, connection, result, handler));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture("Invalid 'token' param"));
        }
    }

    /**
     * Generate an ephemeral token for the logged-in user.
     *
     * @param context The routing context.
     * @param connection A database connection.
     */
    public static void getAuthEphemeral(RoutingContext context,
                                        SQLConnection connection) {
        HttpServerResponse response = context.response();
        Session session = context.get("session");
        EphemeralToken.generate(session.getUser(), connection, tokenResult -> connection.close(res -> {
            if (tokenResult.succeeded()) {
                SecurityEvent.create(context, new SecurityEventType(
                        SecurityEventType.EPHEMERAL_TOKEN_REQUEST), connection, eventRes -> {
                    if (eventRes.failed()) {
                        logger.error("Failed to insert ephemeral token request security event", eventRes.cause());
                    }

                    EphemeralToken token = tokenResult.result();
                    String data = Hex.binToHex(token.getToken());
                    QRCode code = QRCode.from(data).withSize(400, 400);
                    byte[] bytes = code.to(ImageType.PNG).stream().toByteArray();
                    response.putHeader("Content-Type", "image/png");
                    response.end(Buffer.buffer(bytes));
                });
            }
            else {
                Request.writeErrorResponse(response,
                        "Failed to generate ephemeral token: " + tokenResult.cause());
            }
        }));
    }

    /**
     * Submit an ephemeral token in exchange for a session token.
     *
     * @param context The routing context.
     * @param connection A database connection.
     * @param handler The result.
     */
    public static void postAuthEphemeral(RoutingContext context,
                                         SQLConnection connection,
                                         Handler<AsyncResult<JsonNode>> handler) {
        String rawToken = context.request().getFormAttribute("token");
        if (rawToken == null) {
            handler.handle(Future.failedFuture("'token' param missing"));
            return;
        }
        byte[] token = Hex.hexToBin(rawToken);
        EphemeralToken.validate(token, connection,
                (result) -> validateToken(context, connection, result, handler));
    }

    /**
     * Helper method to validate a 2FA token.
     * @param context The routing context.
     * @param connection A database connection.
     * @param userResult The outcome of attempting to retrieve the authenticated user.
     * @param handler The result.
     */
    private static void validateToken(RoutingContext context,
                                      SQLConnection connection,
                                      AsyncResult<User> userResult,
                                      Handler<AsyncResult<JsonNode>> handler) {
        if (userResult.succeeded()) {
            User user = userResult.result();
            Session.create(user, connection, sessionRes -> {
                if (sessionRes.succeeded()) {
                    Session session = sessionRes.result();
                    context.put("session", session);  // so security event can read it in the next step
                    SecurityEvent.create(context, new SecurityEventType(SecurityEventType.USER_LOGIN),
                            connection, eventRes -> {
                        if (eventRes.failed()) {
                            handler.handle(Future.failedFuture(eventRes.cause()));
                            return;
                        }

                        ObjectNode wrapper = Json.FACTORY.objectNode();
                        wrapper.set("session", Json.MAPPER.convertValue(session, JsonNode.class));
                        handler.handle(Future.succeededFuture(wrapper));
                    });
                }
                else {
                    handler.handle(Future.failedFuture(sessionRes.cause()));
                }
            });
        } else {
            handler.handle(Future.failedFuture(userResult.cause()));
        }
    }
}
