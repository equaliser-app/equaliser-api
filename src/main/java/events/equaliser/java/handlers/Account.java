package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.SecurityEvent;
import events.equaliser.java.model.auth.TwoFactorToken;
import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.user.PublicUser;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class Account {

    private static final Logger logger = LoggerFactory.getLogger(Account.class);

    public static void getUser(RoutingContext context,
                               SQLConnection connection,
                               Handler<AsyncResult<JsonNode>> handler) {
        Session session = context.get("session");
        User user = session.getUser();
        JsonNode node = Json.MAPPER.convertValue(user, JsonNode.class);
        handler.handle(Future.succeededFuture(node));
    }

    public static void getSecurityEvents(RoutingContext context,
                                         SQLConnection connection,
                                         Handler<AsyncResult<JsonNode>> handler) {
        Session session = context.get("session");
        User user = session.getUser();

        SecurityEvent.retrieveByUser(user, 10, connection, eventsRes -> {
            if (eventsRes.failed()) {
                handler.handle(Future.failedFuture(eventsRes.cause()));
                return;
            }

            List<SecurityEvent> result = eventsRes.result();
            JsonNode node = Json.MAPPER.convertValue(result, JsonNode.class);
            handler.handle(Future.succeededFuture(node));
        });
    }

    public static void postRegister(RoutingContext context,
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
            Vertx.currentContext().executeBlocking(code -> {
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

    public static void getUsernames(RoutingContext context,
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
}
