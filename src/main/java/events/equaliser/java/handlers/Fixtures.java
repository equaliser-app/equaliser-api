package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import events.equaliser.java.model.event.Fixture;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import events.equaliser.java.verticles.SecondaryPoolVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;

import java.awt.*;

public class Fixtures {

    public static void getId(RoutingContext context,
                             SQLConnection connection,
                             Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        try {
            String rawId = Request.validateField("id", request.getParam("id"));
            int id = Integer.parseInt(rawId);
            Fixture.retrieveFromId(id, connection, res -> {
                if (res.failed()) {
                    handler.handle(Future.failedFuture(res.cause()));
                    return;
                }

                Fixture fixture = res.result();
                JsonNode node = Json.MAPPER.convertValue(fixture, JsonNode.class);
                handler.handle(Future.succeededFuture(node));
            });
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("Invalid fixture id"));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e.getMessage()));
        }
    }

    public static void getAddAvailability(RoutingContext context,
                                          SQLConnection connection,
                                          Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        EventBus eb = Vertx.currentContext().owner().eventBus();
        int tierId = Integer.parseInt(request.getParam("id"));
        int quantity = Integer.parseInt(request.getParam("quantity"));
        eb.send(SecondaryPoolVerticle.SECONDARY_POOL_RECOVER_ADDRESS,
                new JsonObject().put(Integer.toString(tierId), quantity), replyRes -> {
                    if (replyRes.failed()) {
                        handler.handle(Future.failedFuture(replyRes.cause()));
                    }
                    else {
                        handler.handle(Future.succeededFuture(
                                new TextNode(String.format(
                                        "Added %d tickets to secondary availability pool of tier %d",
                                        quantity, tierId))));
                    }
                });
    }
}
