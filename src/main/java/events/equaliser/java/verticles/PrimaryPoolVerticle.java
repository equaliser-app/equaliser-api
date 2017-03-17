package events.equaliser.java.verticles;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class PrimaryPoolVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(PrimaryPoolVerticle.class);

    public static final String PRIMARY_POOL_RESERVE_ADDRESS = "primary_pool.reserve";
    public static final String PRIMARY_POOL_AVAILABILITY_ADDRESS = "primary_pool.availability";
    public static final String PRIMARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS = "primary_pool.availability_multiple";

    AsyncSQLClient client;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"),
                PrimaryPoolVerticle.class.getCanonicalName());
        getInitialData(handler -> marshalInitialData(startFuture, handler));
    }

    protected void marshalInitialData(Future<Void> future,
                                   AsyncResult<Map<Integer, Integer>> g) {
        if (g.failed()) {
            logger.error("Failed to retrieve initial data", g.cause());
            future.fail(g.cause());
            return;
        }
        processInitialData(future, g.result());
    }

    protected void processInitialData(Future<Void> startFuture,
                                      Map<Integer, Integer> result) {
        logger.debug("Configured primary pool with {} tiers", result.size());
        EventBus eb = vertx.eventBus();
        eb.consumer(PRIMARY_POOL_RESERVE_ADDRESS,
                message -> reserve(result, message));
        eb.consumer(PRIMARY_POOL_AVAILABILITY_ADDRESS,
                message -> availability(result, message));
        eb.consumer(PRIMARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS,
                message -> availabilityMultiple(result, message));
        startFuture.complete();
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

    protected void getInitialData(Handler<AsyncResult<Map<Integer, Integer>>> handler) {
        client.getConnection(connRes -> {
            if (connRes.failed()) {
                handler.handle(Future.failedFuture(connRes.cause()));
                return;
            }

            SQLConnection connection = connRes.result();
            // TODO do this properly - this query only works from a clean state
            /*
            For each tier, remaining = available -
               # allocated to a group members (with a valid transaction) without a complete refund -
               # that are part of a currently valid offer
             */
            connection.query(
                    "SELECT " +
                        "TierID, " +
                        "Availability " +
                    "FROM Tiers " +
                    "ORDER BY TierID ASC;", res -> connection.close(closed -> {
                        if (res.failed()) {
                            handler.handle(Future.failedFuture(res.cause()));
                            return;
                        }

                        Map<Integer, Integer> map = new HashMap<>();
                        ResultSet set = res.result();
                        for (JsonObject row : set.getRows()) {
                            map.put(row.getInteger("TierID"),
                                    row.getInteger("Availability"));
                        }
                        handler.handle(Future.succeededFuture(map));
                    }));
        });
    }

    /**
     * Attempt to shotgun a number of tickets. If that number are availability, they are
     * subtracted from the availability number atomically.
     *
     * @param availability Available ticket counts.
     * @param message The incoming message containing the tier of ticket and number requested.
     */
    protected void reserve(Map<Integer, Integer> availability,
                           Message<Object> message) {
        JsonObject payload = (JsonObject)message.body();
        Integer tierId = payload.getInteger("tierId");
        Integer count = payload.getInteger("count");
        logger.debug("Attempting to reserve {} tickets for tier {}", count, tierId);
        Integer remaining = availability.get(tierId);
        boolean success = false;
        if (remaining != null && remaining >= count) {
            availability.put(tierId, remaining - count);
            success = true;
        }
        logger.debug("Succeeded? {}", success);
        message.reply(new JsonObject().put("success", success));
    }

    /**
     * Peek at how many tickets are available for a single tier.
     *
     * @param availability Available ticket counts.
     * @param message The incoming message containing the tier to query.
     */
    protected void availability(Map<Integer, Integer> availability,
                                Message<Object> message) {
        JsonObject payload = (JsonObject)message.body();
        Integer tierId = payload.getInteger("tierId");
        Integer remaining = availability.get(tierId);
        logger.debug("Remaining count for tier {}: {}", tierId, remaining);
        message.reply(new JsonObject().put("remaining", remaining == null ? 0 : remaining));
    }

    /***
     * Find the availability of multiple tiers.
     *
     * @param availability Available ticket counts.
     * @param message The message containing a JSON array of tier IDs.
     */
    protected void availabilityMultiple(Map<Integer, Integer> availability,
                                        Message<Object> message) {
        JsonArray tierIds = (JsonArray)message.body();
        logger.debug("Determining availability of {} tiers", tierIds.size());
        JsonObject response = new JsonObject();
        for (Object idObj :tierIds) {
            Integer id = (Integer)idObj;
            Integer remaining = availability.get(id);
            response.put(id.toString(), remaining == null ? 0 : remaining);
        }
        message.reply(response);
    }
}
