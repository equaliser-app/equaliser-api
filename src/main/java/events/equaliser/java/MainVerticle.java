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
import events.equaliser.java.model.user.PublicUser;
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
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

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
        router.get("/usernames").handler(
                routingContext -> databaseJsonHandler(routingContext, this::usernames));

        router.get("/series/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, this::seriesSingle));

        router.post("/register").handler(
                routingContext -> databaseJsonHandler(routingContext, this::register));

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
        String hexToken = context.request().getHeader("Authorization");
        if (hexToken == null) {
            MainVerticle.writeResponse(
                    context.response(),
                    MainVerticle.errorResponse("Endpoint requires authorisation, but no token provided"),
                    401);
            return;
        }

        // we have a token; time to validate it
        byte[] token = Hex.hexToBin(hexToken);
        Session.retrieveByToken(token, connection, sessionRes -> {
            if (sessionRes.succeeded()) {
                Session session = sessionRes.result();
                System.out.println("Identified " + session);
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
                           Handler<AsyncResult<JsonNode>> handler) {
        Country.retrieveAll(connection, data -> connection.close(closed -> {
            if (data.succeeded()) {
                List<Country> countries = data.result();
                JsonNode node = mapper.convertValue(countries, JsonNode.class);
                // TODO use id => country dict instead of list
                handler.handle(Future.succeededFuture(node));
            } else {
                handler.handle(Future.failedFuture(data.cause()));
            }
        }));
    }

    private void usernames(RoutingContext context,
                           SQLConnection connection,
                           Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        List<String> fields = Collections.singletonList("query");
        try {
            Map<String, String> parsed = parseData(request, fields, MainVerticle::getParam);
            PublicUser.searchByUsername(parsed.get("query"), 5, connection, queryRes -> {
                if (queryRes.succeeded()) {
                    List<PublicUser> users = queryRes.result();
                    JsonNode node = mapper.convertValue(users, JsonNode.class);
                    handler.handle(Future.succeededFuture(node));
                }
                else {
                    handler.handle(Future.failedFuture(queryRes.cause()));
                }
            });
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e.getMessage()));
        }
    }

    private void seriesSingle(RoutingContext context,
                              SQLConnection connection,
                              Handler<AsyncResult<JsonNode>> handler) {
        try {
            String rawId = context.request().getParam("id");
            if (rawId == null) {
                handler.handle(Future.failedFuture("'id' param missing"));
                return;
            }
            int id = Integer.parseInt(rawId);
            Series.retrieveFromId(id, connection, data -> {
                if (data.succeeded()) {
                    Series series = (Series) data.result();  // TODO fix cast - caused by generics erasure issue
                    JsonNode node = mapper.convertValue(series, JsonNode.class);
                    handler.handle(Future.succeededFuture(node));
                }
                else {
                    handler.handle(Future.failedFuture(data.cause()));
                }
            });
        }
        catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("Invalid series id"));
        }
    }

    private static String getFormAttribute(HttpServerRequest request, String field) {
        return request.getFormAttribute(field);
    }

    private static String getParam(HttpServerRequest request, String field) {
        return request.getParam(field);
    }

    private static Map<String, String> parseData(HttpServerRequest request, List<String> names,
                                                 BiFunction<HttpServerRequest, String, String> retriever) {
        Map<String, String> fields = new HashMap<>();
        for (String name : names) {
            String value = retriever.apply(request, name);  // POST (getFormAttribute()) or GET (getParam())
            if (value == null) {
                throw new IllegalArgumentException(String.format("'%s' param missing", name));
            }
            value = value.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(String.format("'%s' param empty", name));
            }
            fields.put(name, value);
        }
        return fields;
    }

    private static void missingParam(String name, Handler<AsyncResult<JsonNode>> handler) {
        handler.handle(Future.failedFuture(String.format("'%s' param missing", name)));
    }

    private void register(RoutingContext context,
                          SQLConnection connection,
                          Handler<AsyncResult<JsonNode>> handler) {
        Set<FileUpload> files = context.fileUploads();
        if (files.size() != 1) {
            handler.handle(Future.failedFuture("1 file should be uploaded"));
            return;
        }
        FileUpload photo = files.iterator().next();
        if (!photo.contentType().equals("image/jpeg")) {
            handler.handle(Future.failedFuture("Image must be a JPEG"));
            return;
        }

        HttpServerRequest request = context.request();
        List<String> fields = Arrays.asList("username", "countryId", "forename", "surname", "email", "areaCode",
                "subscriberNumber", "password");
        try {
            Map<String, String> parsed = parseData(request, fields, MainVerticle::getFormAttribute);
            int countryId = Integer.parseInt(parsed.get("countryId"));
            Country.retrieveById(countryId, connection, countryRes -> {
                if (countryRes.succeeded()) {
                    Country country = countryRes.result();
                    //System.out.println("Retrieved country: " + country);
                    User.register(
                            parsed.get("username"),
                            country, parsed.get("forename"), parsed.get("surname"),
                            parsed.get("email"),
                            parsed.get("areaCode"), parsed.get("subscriberNumber"),
                            parsed.get("password"),
                            photo,
                            connection, registerRes -> initiateTwoFactor(connection, registerRes, handler));
                }
                else {
                    handler.handle(Future.failedFuture(countryRes.cause()));
                }
            });
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("'countryId' must be numeric"));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e.getMessage()));
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
        Credentials.validate(username, password, connection,
                credentials -> initiateTwoFactor(connection, credentials, handler));
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
        String rawToken = context.request().getFormAttribute("token");
        if (rawToken == null) {
            handler.handle(Future.failedFuture("'token' param missing"));
            return;
        }
        byte[] token = Hex.hexToBin(rawToken);
        EphemeralToken.validate(token, connection,
                (result) -> validateToken(connection, result, handler));
    }

    private static void initiateTwoFactor(SQLConnection connection,
                                          AsyncResult<User> userResult,
                                          Handler<AsyncResult<JsonNode>> result) {
        if (userResult.succeeded()) {
            User user = userResult.result();
            //System.out.println("Initiating 2FA for " + user);
            TwoFactorToken.initiate(user, connection, tokenRes -> {
                if (tokenRes.succeeded()) {
                    TwoFactorToken sent = tokenRes.result();
                    ObjectNode wrapper = factory.objectNode();
                    wrapper.set("token", mapper.convertValue(sent, JsonNode.class));
                    result.handle(Future.succeededFuture(wrapper));
                }
                else {
                    result.handle(Future.failedFuture(tokenRes.cause()));
                }
            });
        }
        else {
            result.handle(Future.failedFuture(userResult.cause()));
        }
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
