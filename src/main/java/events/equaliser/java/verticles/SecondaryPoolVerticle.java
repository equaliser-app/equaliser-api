package events.equaliser.java.verticles;

import events.equaliser.java.util.Json;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SecondaryPoolVerticle extends PrimaryPoolVerticle {

    private static final Logger logger = LoggerFactory.getLogger(SecondaryPoolVerticle.class);

    static final String SECONDARY_POOL_AVAILABILITY_ADDRESS = "secondary_pool.availability";
    static final String SECONDARY_POOL_AVAILABILITY_ALL_ADDRESS = "secondary_pool.availability_all";
    static final String SECONDARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS = "secondary_pool.availability_multiple";
    static final String SECONDARY_POOL_RESERVE_ADDRESS = "secondary_pool.reserve";
    static final String SECONDARY_POOL_RECOVER_ADDRESS = "secondary_pool.recover";

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"),
                SecondaryPoolVerticle.class.getCanonicalName());
        getInitialData(handler -> marshalInitialData(startFuture, handler));
    }

    protected void getInitialData(Handler<AsyncResult<Map<Integer, Integer>>> handler) {
        client.getConnection(connRes -> {
            if (connRes.failed()) {
                handler.handle(Future.failedFuture(connRes.cause()));
                return;
            }

            SQLConnection connection = connRes.result();
            // TODO do this properly - this query only works from a clean state
            connection.query(
                    "SELECT TierID " +
                    "FROM Tiers " +
                    "ORDER BY TierID ASC;", res -> connection.close(closed -> {
                        if (res.failed()) {
                            handler.handle(Future.failedFuture(res.cause()));
                            return;
                        }

                        Map<Integer, Integer> map = new HashMap<>();
                        ResultSet set = res.result();
                        for (JsonObject row : set.getRows()) {
                            map.put(row.getInteger("TierID"), 0);
                        }
                        handler.handle(Future.succeededFuture(map));
                    }));
        });
    }

    protected void processInitialData(Future<Void> startFuture,
                                      Map<Integer, Integer> result) {
        logger.debug("Configured secondary pool with {} tiers", result.size());
        EventBus eb = vertx.eventBus();
        eb.consumer(SECONDARY_POOL_AVAILABILITY_ADDRESS,
                message -> availability(result, message));
        eb.consumer(SECONDARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS,
                message -> availabilityMultiple(result, message));
        eb.consumer(SECONDARY_POOL_AVAILABILITY_ALL_ADDRESS,
                message -> availabilityAll(result, message));
        eb.consumer(SECONDARY_POOL_RESERVE_ADDRESS,
                message -> reserve(result, message));
        eb.consumer(SECONDARY_POOL_RECOVER_ADDRESS,
                message -> recover(result, message));
        startFuture.complete();
    }

    /**
     * Return the remaining counts of all tiers with availability tickets.
     *
     * @param availability Available ticket counts.
     * @param message An empty message.
     */
    protected void availabilityAll(Map<Integer, Integer> availability,
                                   Message<Object> message) {
        Map<Integer, Integer> filtered = availability.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        message.reply(Json.toJsonObject(filtered));
    }

    /**
     * Re-add tickets to a tier, for example if an offer has expired.
     *
     * @param availability Available ticket counts.
     * @param message The incoming message containing the tier and the number of tickets to add.
     */
    protected void recover(Map<Integer, Integer> availability,
                           Message<Object> message) {
        JsonObject payload = (JsonObject)message.body();
        boolean success = true;
        for (Map.Entry<String, Object> tier : payload) {
            Integer tierId = Integer.parseInt(tier.getKey());
            Integer reclaim = (Integer)tier.getValue();
            if (!availability.containsKey(tierId)) {
                success = false;
                continue;
            }
            availability.put(tierId, availability.get(tierId) + reclaim);
        }
        message.reply(new JsonObject().put("success", success));
    }
}
