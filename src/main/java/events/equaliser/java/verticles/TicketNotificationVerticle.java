package events.equaliser.java.verticles;

import co.paralleluniverse.fibers.Suspendable;
import com.twilio.rest.api.v2010.account.Message;
import events.equaliser.java.model.event.Tier;
import events.equaliser.java.model.ticket.Ticket;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sync.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically sends notifications for issued tickets.
 */
public class TicketNotificationVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(TicketNotificationVerticle.class);

    private static final int INTERVAL_MILLIS = 30_000;

    private AsyncSQLClient client;

    @Override
    @Suspendable
    public void start(Future<Void> startFuture) throws Exception {
        client = MySQLClient.createShared(vertx,
                config().getJsonObject("database"),
                TicketNotificationVerticle.class.getCanonicalName());

        Handler<Long> handler = new Handler<Long>() {
            @Override
            public void handle(Long event) {
                /*
                This is fun: if execute() fails in an async bit, it will respond via the handler, otherwise it throws an exception.

                Actually, you have no idea, and neither does the internet.
                 */
                try {
                    execute(res -> {
                        if (res.succeeded()) {
                            logger.debug("{} finished periodic run successfully",
                                    TicketNotificationVerticle.class.getName());
                        } else {
                            logger.error("finished prematurely with error", res.cause());
                        }
                        vertx.setTimer(INTERVAL_MILLIS, this);
                    });
                } catch (VertxException e) {
                    logger.error("{} finished with error", e);
                }
            }
        };
        vertx.setTimer(INTERVAL_MILLIS, handler);
        startFuture.complete();
    }

    @Override
    @Suspendable
    public void stop(Future<Void> stopFuture) throws Exception {
        client.close(handler -> {
            if (handler.succeeded()) {
                stopFuture.complete();
            } else {
                stopFuture.fail(handler.cause());
            }
        });
    }

    private void execute(Handler<AsyncResult<Void>> handler) throws VertxException {
        client.getConnection(connRes -> {
            if (connRes.failed()) {
                handler.handle(Future.failedFuture(connRes.cause()));
                return;
            }

            SQLConnection connection = connRes.result();
            connection.query(
                    "SELECT " +
                        "Tickets.TicketID, " +
                        "Tickets.NotificationSent AS TicketNotificationSent, " +
                        "Tiers.TierID, " +
                        "Users.UserID, " +
                        "Users.Username AS UserUsername, " +
                        "Users.Forename AS UserForename, " +
                        "Users.Surname AS UserSurname, " +
                        "Users.Email AS UserEmail, " +
                        "Users.AreaCode AS UserAreaCode, " +
                        "Users.SubscriberNumber AS UserSubscriberNumber, " +
                        "Users.Token AS UserToken, " +
                        "Users.ImageID AS UserImageID, " +
                        "Countries.CountryID, " +
                        "Countries.Name AS CountryName, " +
                        "Countries.Abbreviation AS CountryAbbreviation, " +
                        "Countries.CallingCode AS CountryCallingCode " +
                    "FROM Tickets " +
                        "INNER JOIN Users " +
                            "ON Users.UserID = Tickets.UserID " +
                        "INNER JOIN Countries " +
                            "ON Countries.CountryID = Users.CountryID " +
                        "INNER JOIN Transactions " +
                            "ON Transactions.TransactionID = Tickets.TransactionID " +
                        "INNER JOIN Offers " +
                            "ON Offers.OfferID = Transactions.OfferID " +
                        "INNER JOIN Tiers " +
                            "ON Tiers.TierID = Offers.TierID " +
                    "WHERE Tickets.NotificationSent IS NULL;", Sync.fiberHandler(ticketsRes -> {
                        if (ticketsRes.failed()) {
                            handler.handle(Future.failedFuture(ticketsRes.cause()));
                            return;
                        }

                        ResultSet results = ticketsRes.result();
                        if (results.getNumRows() == 0) {
                            logger.debug("Returning early as nothing to do");
                            connection.close(closeRes -> handler.handle(Future.succeededFuture()));
                            return;
                        }

                        for (JsonObject row : results.getRows()) {
                            Ticket ticket = Ticket.fromJsonObject(row);
                            int tierId = row.getInteger("TierID");
                            try {
                                Tier tier = Sync.awaitResult(h -> Tier.retrieveById(tierId, connection, h));
                                Message message = Sync.awaitResult(h -> ticket.sendNotification(tier, connection, h));
                                logger.debug("Sent ticket notification for {}", ticket);
                            } catch (VertxException e) {
                                logger.error("Error processing ticket", e);
                            }
                        }

                        Void v = Sync.awaitResult(connection::close);
                        handler.handle(Future.succeededFuture());
                    }));
        });
    }
}
