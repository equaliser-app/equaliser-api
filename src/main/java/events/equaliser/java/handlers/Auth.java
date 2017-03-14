package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import events.equaliser.java.auth.Credentials;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.EphemeralToken;
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

public class Auth {

    private static final Logger logger = LoggerFactory.getLogger(Auth.class);

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

    public static void postAuthSecond(RoutingContext context,
                                      SQLConnection connection,
                                      Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
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
                (result) -> validateToken(connection, result, handler));
    }

    public static void getAuthEphemeral(RoutingContext context,
                                        SQLConnection connection) {
        HttpServerResponse response = context.response();
        Session session = context.get("session");
        EphemeralToken.generate(session.getUser(), connection, tokenResult -> {
            if (tokenResult.succeeded()) {
                EphemeralToken token = tokenResult.result();
                String data = Hex.binToHex(token.getToken());
                QRCode code = QRCode.from(data).withSize(400, 400);
                byte[] bytes = code.to(ImageType.PNG).stream().toByteArray();
                response.putHeader("Content-Type", "image/png");
                response.end(Buffer.buffer(bytes));
            }
            else {
                Request.writeErrorResponse(response, "Failed to generate ephemeral token: " + tokenResult.cause());
            }
        });
    }

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
                (result) -> validateToken(connection, result, handler));
    }

    private static void validateToken(SQLConnection connection,
                                      AsyncResult<User> userResult,
                                      Handler<AsyncResult<JsonNode>> result) {
        if (userResult.succeeded()) {
            User user = userResult.result();
            Session.create(user, connection, sessionRes -> {
                if (sessionRes.succeeded()) {
                    Session session = sessionRes.result();
                    ObjectNode wrapper = Json.FACTORY.objectNode();
                    wrapper.set("session", Json.MAPPER.convertValue(session, JsonNode.class));
                    result.handle(Future.succeededFuture(wrapper));
                }
                else {
                    result.handle(Future.failedFuture(sessionRes.cause()));
                }
            });
        } else {
            result.handle(Future.failedFuture(userResult.cause()));
        }
    }
}
