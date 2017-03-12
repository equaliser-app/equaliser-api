package events.equaliser.java;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.EphemeralToken;
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
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import net.glxn.qrgen.javase.QRCode;

import java.util.List;
import java.util.function.BiConsumer;

public class MainVerticle extends AbstractVerticle {

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;

    private AsyncSQLClient client;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JsonNodeFactory factory = JsonNodeFactory.instance;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        JsonObject config = config().getJsonObject("database");
        client = MySQLClient.createShared(vertx, config);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(5 * MB));
        router.route().handler(SessionHandler
                .create(LocalSessionStore.create(vertx))
                .setCookieHttpOnlyFlag(true)
                .setSessionTimeout(Long.MAX_VALUE)); // TODO .setCookieSecureFlag(true)

        // TODO add security headers: http://vertx.io/blog/writing-secure-vert-x-web-apps/

        router.get("/countries").handler(
                routingContext -> databaseJsonHandler(routingContext, this::countries));
        router.get("/series/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, this::seriesSingle));
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
        HttpServerResponse response = context.response();
        client.getConnection(connection -> {
            if (connection.succeeded()) {
                consumer.accept(context, connection.result());
            }
            else {
                writeErrorResponse(response,"Failed to get a database connection from the pool");
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
            int id = Integer.parseInt(context.request().getParam("id"));
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

    private void ephemeralGet(RoutingContext context,
                              SQLConnection connection) {
        HttpServerResponse response = context.response();
        User user = context.get("user");
        EphemeralToken.generate(user, connection, tokenResult -> {
            if (tokenResult.succeeded()) {
                EphemeralToken token = tokenResult.result();
                QRCode code = QRCode.from(Hex.binToHex(token.getToken())).withSize(300, 300);
                Buffer buffer = Buffer.buffer(code.stream().toByteArray());
                response.putHeader("Content-Type", "image/png");
                response.end(buffer);
            }
            else {
                writeErrorResponse(response, "Failed to generate ephemeral token");
            }
        });
    }

    private void ephemeralPost(RoutingContext context,
                               SQLConnection connection,
                               Handler<AsyncResult<JsonNode>> result) {
        String ephemeral_token = context.request().getFormAttribute("ephemeral_token");
        if (ephemeral_token == null) {
            result.handle(Future.failedFuture("Required param 'ephemeral_token' missing"));
        }
        else {
            EphemeralToken.validate(ephemeral_token, connection, validationResult -> {
                if (validationResult.succeeded()) {
                    User user = validationResult.result();
                    ObjectNode node = factory.objectNode();
                    node.put("session_token", "C8C2E98B83198235C84A48440A08162E31FBE73495232FB0F449A390D66A2342");
                    node.set("user", mapper.convertValue(user, JsonNode.class));
                    result.handle(Future.succeededFuture(node));
                } else {
                    result.handle(Future.failedFuture(validationResult.cause()));
                }
            });
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
