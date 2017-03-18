package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.auth.SecurityEvent;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Json;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;

import java.util.List;


public class Account {

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
}
