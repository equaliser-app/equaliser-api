package events.equaliser.java.verticles;

import events.equaliser.java.util.Json;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Periodically scans for expired unclaimed offers and adds the tickets behind them to the secondary pool.
 */
public class OfferReclaimVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(OfferReclaimVerticle.class);

    private static final int INTERVAL_MILLIS = 30_000;

    private AsyncSQLClient client;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"),
                OfferReclaimVerticle.class.getCanonicalName());

        Handler<Long> handler = new Handler<Long>() {
            @Override
            public void handle(Long event) {
                execute(res -> {
                    if (res.succeeded()) {
                        logger.debug("{} finished periodic run successfully", OfferReclaimVerticle.class.getName());
                    }
                    else {
                        logger.error("{} finished prematurely with error", res.cause());
                    }
                    vertx.setTimer(INTERVAL_MILLIS, this);
                });
            }
        };
        vertx.setTimer(INTERVAL_MILLIS, handler);
        startFuture.complete();
    }

    private void execute(Handler<AsyncResult<Void>> handler) {
        client.getConnection(connRes -> {
            if (connRes.failed()) {
                handler.handle(Future.failedFuture(connRes.cause()));
                return;
            }

            SQLConnection connection = connRes.result();
            connection.query(
                    "SELECT " +
                        "Offers.OfferID, " +
                        "Offers.TierID, " +
                        "COUNT(*) AS TicketCount " +
                    "FROM Offers " +
                        "INNER JOIN Groups " +
                            "ON Groups.GroupID = Offers.GroupID " +
                        "INNER JOIN PaymentGroups " +
                            "ON PaymentGroups.GroupID = Groups.GroupID " +
                        "INNER JOIN PaymentGroupAttendees " +
                            "ON PaymentGroupAttendees.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                    "WHERE Offers.IsReclaimed = false " +
                        "AND Offers.Expires < NOW() " +
                    "GROUP BY Offers.OfferID, Offers.TierID;", queryRes -> {
                        if (queryRes.failed()) {
                            handler.handle(Future.failedFuture(queryRes.cause()));
                            return;
                        }

                        ResultSet resultSet = queryRes.result();
                        if (resultSet.getNumRows() == 0) {
                            // N.B. this must be here - IN clause below requires non-empty list
                            logger.debug("Returning early as nothing to do");
                            connection.close(closeRes -> handler.handle(Future.succeededFuture()));
                            return;
                        }

                        Set<Integer> offerIds = new HashSet<>();
                        Map<Integer, Integer> reclaim = new HashMap<>();
                        for (JsonObject row : resultSet.getRows()) {
                            offerIds.add(row.getInteger("OfferID"));
                            Integer tierId = row.getInteger("TierID");
                            if (!reclaim.containsKey(tierId)) {
                                reclaim.put(tierId, 0);
                            }
                            reclaim.put(tierId, reclaim.get(tierId) + row.getInteger("TicketCount"));
                        }

                        logger.debug("Offer reclaim will reintroduce {} tickets from {} expired, unaccepted offers",
                                reclaim.entrySet().stream().mapToInt(Map.Entry::getValue).sum(), offerIds.size());

                        EventBus bus = vertx.eventBus();
                        bus.send(SecondaryPoolVerticle.SECONDARY_POOL_RECOVER_ADDRESS,
                                Json.toJsonObject(reclaim));

                        String offerIdsStr = offerIds.stream()
                                .map(Object::toString)
                                .map(str -> '\'' + str + '\'')
                                .collect(Collectors.joining(","));
                        connection.update(
                                String.format(
                                        "UPDATE Offers SET IsReclaimed = true WHERE OfferID IN (%s);", offerIdsStr),
                                updateRes -> connection.close(closeRes -> {
                                    if (updateRes.failed()) {
                                        handler.handle(Future.failedFuture(updateRes.cause()));
                                        return;
                                    }
                                    handler.handle(Future.succeededFuture());
                                }));
                    });
        });
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
