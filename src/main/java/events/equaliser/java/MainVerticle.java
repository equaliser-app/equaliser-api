package events.equaliser.java;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.twilio.Twilio;
import events.equaliser.java.auth.Credentials;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.EphemeralToken;
import events.equaliser.java.model.auth.TwoFactorToken;
import events.equaliser.java.model.event.Series;
import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.TriConsumer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

public class MainVerticle extends AbstractVerticle {

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;

    private AsyncSQLClient client;
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final JsonNodeFactory factory = JsonNodeFactory.instance;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        JsonObject config = config();

        client = MySQLClient.createShared(vertx, config.getJsonObject("database"));

        JsonObject twilio = config.getJsonObject("twilio");
        Twilio.init(
                twilio.getString("sid"),
                twilio.getString("authToken"));

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(5 * MB));

        // TODO add security headers: http://vertx.io/blog/writing-secure-vert-x-web-apps/

        router.get("/countries").handler(
                routingContext -> databaseJsonHandler(routingContext, this::countries));
        router.get("/series/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, this::seriesSingle));

        router.post("/auth/first").handler(
                routingContext -> databaseJsonHandler(routingContext, this::authFirst));
        router.post("/auth/second").handler(
                routingContext -> databaseJsonHandler(routingContext, this::authSecond));
        router.post("/auth/ephemeral").handler(
                routingContext -> databaseJsonHandler(routingContext, this::ephemeralPost));

        // all endpoints past this point require authentication
        router.route().handler(
                routingContext -> databaseHandler(routingContext, this::authenticate));

        router.get("/auth/ephemeral").handler(
                routingContext -> databaseHandler(routingContext, this::ephemeralGet));

        final int listenPort = config().getJsonObject("webserver").getInteger("port");
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router::accept).listen(listenPort, handler -> {
            if (!handler.succeeded()) {
                System.err.println("Failed to listen on port " + listenPort);
            }
        });

        super.start(startFuture);
    }

    private void databaseHandler(RoutingContext context,
                                 BiConsumer<RoutingContext, SQLConnection> consumer) {
        client.getConnection(connection -> {
            if (connection.succeeded()) {
                consumer.accept(context, connection.result());
            }
            else {
                writeErrorResponse(context.response(),"Failed to get a database connection from the pool");
            }
        });
    }

    private void databaseJsonHandler(RoutingContext context,
                                     TriConsumer<RoutingContext,
                                         SQLConnection,
                                         Handler<AsyncResult<JsonNode>>> consumer) {
        databaseHandler(context, (routingContext, connection) -> consumer.accept(context, connection, done -> {
            HttpServerResponse response = context.response();
            if (done.succeeded()) {
                writeSuccessResponse(response, done.result());
            }
            else {
                writeErrorResponse(response, done.cause().toString());
            }
        }));
    }

    public static ObjectNode errorResponse(String message) {
        ObjectNode container = factory.objectNode();
        container.put("success", false);
        container.put("message", message);
        return container;
    }

    private static void writeSuccessResponse(HttpServerResponse response, JsonNode data) {
        ObjectNode container = factory.objectNode();
        container.put("success", true);
        container.set("result", data);
        writeResponse(response, container, 200);
    }

    private static void writeErrorResponse(HttpServerResponse response, String message) {
        writeResponse(response, errorResponse(message), 500);
    }

    public static void writeResponse(HttpServerResponse response, JsonNode node, int statusCode) {
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.setStatusCode(statusCode);
        String text;
        try {
            text = mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // should be impossible, but we're covered
            text = "Server error";
        }
        response.end(text);
    }

    private void authenticate(RoutingContext context,
                              SQLConnection connection) {
        // look for the authentication token
        String hex_token = context.request().getHeader("Authorization");
        if (hex_token == null) {
            MainVerticle.writeResponse(
                    context.response(),
                    MainVerticle.errorResponse("Endpoint requires authorisation, but no token provided"),
                    401);
            return;
        }

        // we have a token; time to validate it
        byte[] token = Hex.hexToBin(hex_token);
        Session.retrieveByToken(token, connection, sessionRes -> {
            if (sessionRes.succeeded()) {
                Session session = sessionRes.result();
                context.put("session", session);
                context.next();
            }
            else {
                MainVerticle.writeResponse(
                        context.response(),
                        MainVerticle.errorResponse(sessionRes.cause().toString()),
                        401);
            }
        });
    }

    private void countries(RoutingContext context,
                           SQLConnection connection,
                           Handler<AsyncResult<JsonNode>> result) {
        Country.retrieveAll(connection, data -> connection.close(closed -> {
            if (data.succeeded()) {
                List<Country> countries = data.result();
                JsonNode node = mapper.convertValue(countries, JsonNode.class);
                // TODO use id => country dict instead of list
                result.handle(Future.succeededFuture(node));
            } else {
                result.handle(Future.failedFuture(data.cause()));
            }
        }));
    }

    private void seriesSingle(RoutingContext context,
                              SQLConnection connection,
                              Handler<AsyncResult<JsonNode>> result) {
        try {
            String id_raw = context.request().getParam("id");
            if (id_raw == null) {
                result.handle(Future.failedFuture("'id' param missing"));
                return;
            }
            int id = Integer.parseInt(id_raw);
            Series.retrieveFromId(id, connection, data -> {
                if (data.succeeded()) {
                    Series series = (Series) data.result();  // TODO fix cast - caused by generics erasure issue
                    JsonNode node = mapper.convertValue(series, JsonNode.class);
                    result.handle(Future.succeededFuture(node));
                }
                else {
                    result.handle(Future.failedFuture(data.cause()));
                }
            });
        }
        catch (NumberFormatException e) {
            result.handle(Future.failedFuture("Invalid series id"));
        }
    }

    private void authFirst(RoutingContext context,
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
        Credentials.validate(username, password, connection, credentials -> {
            if (credentials.succeeded()) {
                User user = credentials.result();
                TwoFactorToken.initiate(user, connection, token -> {
                    if (token.succeeded()) {
                        TwoFactorToken sent = token.result();
                        JsonNode node = mapper.convertValue(sent, JsonNode.class);
                        handler.handle(Future.succeededFuture(node));
                    }
                    else {
                        handler.handle(Future.failedFuture(token.cause()));
                    }
                });
            }
            else {
                handler.handle(Future.failedFuture(credentials.cause()));
            }
        });
    }

    private void authSecond(RoutingContext context,
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

    private void ephemeralGet(RoutingContext context,
                              SQLConnection connection) {
        HttpServerResponse response = context.response();
        Session session = context.get("session");
        EphemeralToken.generate(session.getUser(), connection, tokenResult -> {
            if (tokenResult.succeeded()) {
                EphemeralToken token = tokenResult.result();
                String data = Hex.binToHex(token.getToken());
                System.out.println("Data: " + data);
                QRCode code = QRCode.from(data).withSize(400, 400);
                byte[] bytes = code.to(ImageType.PNG).stream().toByteArray();
                response.putHeader("Content-Type", "image/png");
                response.end(Buffer.buffer(bytes));
            }
            else {
                writeErrorResponse(response, "Failed to generate ephemeral token: " + tokenResult.cause());
            }
        });
    }

    private void ephemeralPost(RoutingContext context,
                               SQLConnection connection,
                               Handler<AsyncResult<JsonNode>> handler) {
        String raw_token = context.request().getFormAttribute("token");
        if (raw_token == null) {
            handler.handle(Future.failedFuture("'token' param missing"));
            return;
        }
        byte[] token = Hex.hexToBin(raw_token);
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
                    ObjectNode wrapper = factory.objectNode();
                    wrapper.set("session", mapper.convertValue(session, JsonNode.class));
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

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        client.close(handler -> {
            if (handler.succeeded()) {
                stopFuture.complete();
            }
            else {
                stopFuture.fail(handler.cause());
            }
        });
    }
}
