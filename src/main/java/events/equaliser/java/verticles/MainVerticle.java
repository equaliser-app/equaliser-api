package events.equaliser.java.verticles;

import com.fasterxml.jackson.databind.JsonNode;
import com.twilio.Twilio;
import events.equaliser.java.auth.Session;
import events.equaliser.java.handlers.*;
import events.equaliser.java.model.auth.TwoFactorToken;
import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.user.PublicUser;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import events.equaliser.java.util.TriConsumer;
import io.vertx.core.*;
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
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;

public class MainVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    private static final List<Verticle> VERTICLES = Arrays.asList(
            new PrimaryPoolVerticle(),
            new SecondaryPoolVerticle(),
            new OfferIssueVerticle(),
            new OfferReclaimVerticle(),
            new TicketNotificationVerticle());

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;

    private AsyncSQLClient client;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"), MainVerticle.class.getCanonicalName());

        JsonObject twilio = config().getJsonObject("twilio");
        Twilio.init(
                twilio.getString("sid"),
                twilio.getString("authToken"));

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(5 * MB));
        router.route().handler(context -> {
            HttpServerRequest request = context.request();
            logger.info("{} {}", request.method(), request.uri());
            context.next();
        });
        router.route("/images/*").handler(StaticHandler.create()
                .setWebRoot("images"));

        // TODO add security headers: http://vertx.io/blog/writing-secure-vert-x-web-apps/

        router.get("/series/tag/:tag").handler(
                routingContext -> databaseJsonHandler(routingContext, Series::getByTag));
        router.get("/series/showcase").handler(
                routingContext -> databaseJsonHandler(routingContext, Series::getShowcase));
        router.get("/series/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, Series::getId));
        router.get("/fixtures/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, Fixtures::getId));
        router.get("/countries").handler(
                routingContext -> databaseJsonHandler(routingContext, this::getCountries));
        router.route("/countries/*").handler(StaticHandler.create()
                .setWebRoot("countries"));
        router.get("/usernames").handler(
                routingContext -> databaseJsonHandler(routingContext, this::getUsernames));

        router.post("/register").handler(
                routingContext -> databaseJsonHandler(routingContext, this::postRegister));

        router.post("/auth/first").handler(
                routingContext -> databaseJsonHandler(routingContext, Auth::postAuthFirst));
        router.post("/auth/second").handler(
                routingContext -> databaseJsonHandler(routingContext, Auth::postAuthSecond));
        router.post("/auth/ephemeral").handler(
                routingContext -> databaseJsonHandler(routingContext, Auth::postAuthEphemeral));

        // all endpoints past this point require authentication
        router.route().handler(
                routingContext -> databaseHandler(routingContext, this::authenticate));

        router.get("/auth/ephemeral").handler(
                routingContext -> databaseHandler(routingContext, Auth::getAuthEphemeral));
        router.post("/group/create").handler(
                routingContext -> databaseJsonHandler(routingContext, Group::postCreate));
        router.get("/group/list").handler(
                routingContext -> databaseJsonHandler(routingContext, Group::getList));
        router.post("/group/:id/tiers").handler(
                routingContext -> databaseJsonHandler(routingContext, Group::postTiers));
        router.post("/group/:id/pay").handler(
                routingContext -> databaseJsonHandler(routingContext, Group::postPay));
        router.get("/group/:id").handler(
                routingContext -> databaseJsonHandler(routingContext, Group::getId));

        router.get("/account/user").handler(
                routingContext -> databaseJsonHandler(routingContext, Account::getUser));
        router.get("/account/security-events").handler(
                routingContext -> databaseJsonHandler(routingContext, Account::getSecurityEvents));

        List<Future> futures = new ArrayList<>();
        for (Verticle verticle : VERTICLES) {
            Future<String> future = Future.future();
            vertx.deployVerticle(verticle, new DeploymentOptions().setConfig(config()), future.completer());
            futures.add(future);
        }
        CompositeFuture.all(futures).setHandler(launched -> {
            if (launched.failed()) {
                logger.error("Failed to launch one or more verticles", launched.cause());
            }
            else {
                int listenPort = config()
                        .getJsonObject("webserver")
                        .getInteger("port");
                HttpServer server = vertx.createHttpServer();
                server.requestHandler(router::accept).listen(listenPort, handler -> {
                    if (!handler.succeeded()) {
                        logger.error("Failed to listen on port {}", listenPort);
                    }
                    startFuture.complete();
                });
            }
        });
    }

    // REMEMBER TO CLOSE THE CONNECTION WHEN YOU'VE FINISHED WITH IT!
    private void databaseHandler(RoutingContext context,
                                 BiConsumer<RoutingContext, SQLConnection> consumer) {
        client.getConnection(connection -> {
            if (connection.succeeded()) {
                consumer.accept(context, connection.result());
            }
            else {
                Request.writeErrorResponse(context.response(),"Failed to get a database connection from the pool");
            }
        });
    }

    // CONNECTION CLOSED FOR YOU - DON'T CLOSE IT
    private void databaseJsonHandler(RoutingContext context,
                                     TriConsumer<RoutingContext,
                                         SQLConnection,
                                         Handler<AsyncResult<JsonNode>>> consumer) {
        databaseHandler(context, (routingContext, connection) ->
                consumer.accept(context, connection, done -> connection.close(closed -> {
            HttpServerResponse response = context.response();
            if (done.succeeded()) {
                Request.writeSuccessResponse(response, done.result());
            }
            else {
                logger.error("Request finished with error", done.cause());
                Request.writeErrorResponse(response, done.cause().getMessage());
            }
        })));
    }

    private void authenticate(RoutingContext context,
                              SQLConnection connection) {
        // look for the authentication token
        String hexToken = context.request().getHeader("Authorization");
        if (hexToken == null) {
            connection.close(res -> Request.writeResponse(
                    context.response(),
                    Request.errorResponse("Endpoint requires authorisation, but no token provided"),
                    401));
            return;
        }

        // we have a token; time to validate it
        try {
            byte[] token = Hex.hexToBin(hexToken);
            Session.retrieveByToken(token, connection, sessionRes -> connection.close(res -> {
                if (sessionRes.succeeded()) {
                    Session session = sessionRes.result();
                    logger.debug("Identified session {}", session);
                    context.put("session", session);
                    context.next();
                }
                else {
                    Request.writeResponse(
                            context.response(),
                            Request.errorResponse(sessionRes.cause().toString()),
                            401);
                }
            }));
        } catch (IllegalArgumentException e) {
            Request.writeResponse(
                    context.response(),
                    Request.errorResponse("Malformed session token"),
                    400);
        }
    }

    private void getCountries(RoutingContext context,
                              SQLConnection connection,
                              Handler<AsyncResult<JsonNode>> handler) {
        logger.info("Showing countries");
        Country.retrieveAll(connection, data -> {
            if (data.succeeded()) {
                List<Country> countries = data.result();
                JsonNode node = Json.MAPPER.convertValue(countries, JsonNode.class);
                handler.handle(Future.succeededFuture(node));
            } else {
                handler.handle(Future.failedFuture(data.cause()));
            }
        });
    }

    private void getUsernames(RoutingContext context,
                              SQLConnection connection,
                              Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        List<String> fields = Collections.singletonList("query");
        try {
            Map<String, String> parsed = Request.parseData(request, fields, Request::getParam);
            PublicUser.searchByUsername(parsed.get("query"), 5, connection, queryRes -> {
                if (queryRes.succeeded()) {
                    List<PublicUser> users = queryRes.result();
                    JsonNode node = Json.MAPPER.convertValue(users, JsonNode.class);
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

    private void postRegister(RoutingContext context,
                              SQLConnection connection,
                              Handler<AsyncResult<JsonNode>> handler) {
        Set<FileUpload> files = context.fileUploads();
        if (files.size() == 0) {
            handler.handle(Future.failedFuture("No photo uploaded"));
            return;
        }
        FileUpload photo = files.iterator().next();
        if (!photo.contentType().equals("image/jpeg") || !photo.fileName().endsWith(".jpg")) {
            handler.handle(Future.failedFuture("Image must be a JPEG"));
            return;
        }

        HttpServerRequest request = context.request();
        List<String> fields = Arrays.asList("username", "countryId", "forename", "surname", "email", "areaCode",
                "subscriberNumber", "password");
        try {
            Map<String, String> parsed = Request.parseData(request, fields, Request::getFormAttribute);
            int countryId = Integer.parseInt(parsed.get("countryId"));

            // parameters are all there; now ensure the image is acceptable
            vertx.executeBlocking(code -> {
                try {
                    logger.debug("Uploaded file: {}, {} bytes",
                            photo.uploadedFileName(), photo.size());
                    BufferedImage image = ImageIO.read(new File(photo.uploadedFileName()));
                    Files.delete(Paths.get(photo.uploadedFileName()));
                    if (image == null) {
                        code.fail("Unreadable image");
                    }
                    else {
                        User.validateProfilePhoto(image);
                        code.complete(image);
                    }
                } catch (IOException e) {
                    code.fail(e);
                }
            }, codeRes -> {
                if (codeRes.failed()) {
                    handler.handle(Future.failedFuture(codeRes.cause()));
                    return;
                }

                BufferedImage image = (BufferedImage)codeRes.result();
                Country.retrieveById(countryId, connection, countryRes -> {
                    if (countryRes.failed()) {
                        handler.handle(Future.failedFuture(countryRes.cause()));
                        return;
                    }

                    Country country = countryRes.result();
                    logger.debug("Identified {}", country);
                    User.register(
                            parsed.get("username"),
                            country, parsed.get("forename"), parsed.get("surname"),
                            parsed.get("email"),
                            parsed.get("areaCode"), parsed.get("subscriberNumber"),
                            parsed.get("password"),
                            image,
                            connection,
                            registerRes -> TwoFactorToken.initiateTwoFactor(connection, registerRes, handler));
                });
            });
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("'countryId' must be numeric"));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e.getMessage()));
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
