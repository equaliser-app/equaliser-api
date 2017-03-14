package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import events.equaliser.java.model.event.Fixture;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;

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
}
