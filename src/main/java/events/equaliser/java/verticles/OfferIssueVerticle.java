package events.equaliser.java.verticles;

import co.paralleluniverse.fibers.Suspendable;
import events.equaliser.java.model.event.Tier;
import events.equaliser.java.model.group.Group;
import events.equaliser.java.model.ticket.Offer;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sync.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically assigns tickets in the secondary pool to groups in the waiting list.
 * If deemed necessary, this will also process pending refunds to free up more tickets.
 * This verticle is synchronous as it's so procedural.
 */
public class OfferIssueVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(OfferIssueVerticle.class);

    private static final int INTERVAL_MILLIS = 30_000;

    private AsyncSQLClient client;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"),
                OfferIssueVerticle.class.getCanonicalName());

        Handler<Long> handler = new Handler<Long>() {
            @Override
            public void handle(Long event) {
                execute(res -> {
                    if (res.succeeded()) {
                        logger.debug("finished periodic run successfully");
                    }
                    else {
                        logger.error("finished with error", res.cause());
                    }
                    vertx.setTimer(INTERVAL_MILLIS, this);
                });
            }
        };
        vertx.setTimer(INTERVAL_MILLIS, handler);
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

    @Suspendable
    private void execute(Handler<AsyncResult<Void>> handler) {

        client.getConnection(connRes -> {
            if (connRes.failed()) {
                handler.handle(Future.failedFuture(connRes.cause()));
                return;
            }

            SQLConnection connection = connRes.result();
            connection.query(
                    "SELECT " +
                        "WaitingListAttendees.GroupID, " +
                        "WaitingListAttendees.Attendees, " +
                        "GroupTiers.TierID " +
                    "FROM ( " +
                            "SELECT " +
                                "WaitingList.GroupID, " +
                                "WaitingList.Created, " +
                                "COUNT(*) AS Attendees " +
                            "FROM ( " +
                                    "SELECT " +
                                        "Groups.GroupID, " +
                                        "Groups.Created " +
                                    "FROM Groups " +
                                        "LEFT OUTER JOIN Offers " +
                                            "ON Offers.GroupID = Groups.GroupID " +
                                    "WHERE Offers.OfferID IS NULL " +
                                    "ORDER BY Groups.Created ASC) AS WaitingList " +
                                "INNER JOIN PaymentGroups " +
                                    "ON PaymentGroups.GroupID = WaitingList.GroupID " +
                                "INNER JOIN PaymentGroupAttendees " +
                                    "ON PaymentGroupAttendees.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                            "GROUP BY WaitingList.GroupID, WaitingList.Created) AS WaitingListAttendees " +
                        "INNER JOIN GroupTiers " +
                            "ON GroupTiers.GroupID = WaitingListAttendees.GroupID " +
                    "ORDER BY " +
                        "WaitingListAttendees.Created ASC, " +
                        "GroupTiers.Rank ASC;", groupsRes -> connection.close(Sync.fiberHandler(closeRes -> {
                        if (groupsRes.failed()) {
                            handler.handle(Future.failedFuture(groupsRes.cause()));
                            return;
                        }

                        ResultSet results = groupsRes.result();
                        if (results.getNumRows() == 0) {
                            logger.debug("Returning early as nothing to do");
                            handler.handle(Future.succeededFuture());
                            return;
                        }

                        Integer lastOfferedGroup = -1;
                        for (JsonObject row : results.getRows()) {
                            Integer groupId = row.getInteger("GroupID");
                            Integer attendees = row.getInteger("Attendees");
                            Integer tierId = row.getInteger("TierID");

                            if (groupId.equals(lastOfferedGroup)) {
                                // we've already made this group an offer; skip past remaining tiers
                                continue;
                            }

                            try {
                                // see if we can make this group an offer for their next choice of tier (may be 1st)
                                EventBus eb = vertx.eventBus();
                                Message<JsonObject> reservation = Sync.awaitResult(h ->
                                        eb.send(SecondaryPoolVerticle.SECONDARY_POOL_RESERVE_ADDRESS,
                                                new JsonObject().put("tierId", tierId).put("count", attendees), h));

                                JsonObject replyJson = reservation.body();
                                if (!replyJson.getBoolean("success")) {
                                    // there aren't enough tickets left for tierId to fulfil their order
                                    continue;
                                }

                                // tickets are reserved - we can make this group an offer
                                Group group = Sync.awaitResult(h -> Group.retrieveById(groupId, connection, h));
                                Tier tier = Sync.awaitResult(h -> Tier.retrieveById(tierId, connection, h));
                                Offer offer = Sync.awaitResult(h -> Offer.create(group, tier, connection, h));
                                offer.sendNotificationsSync(connection);

                                lastOfferedGroup = groupId;
                            } catch (VertxException e) {
                                // we cannot continue, as that would be unfair to this group
                                handler.handle(Future.failedFuture(e));
                                return;
                            }
                        }

                        handler.handle(Future.succeededFuture());

                        // TODO check if waiting list is empty; if it still isn't, process applicable refunds
                        //      will have to remove db close
                    })));
        });
    }
}
