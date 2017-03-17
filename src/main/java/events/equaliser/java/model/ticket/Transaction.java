package events.equaliser.java.model.ticket;

import com.github.mauricio.async.db.mysql.exceptions.MySQLException;
import events.equaliser.java.model.group.PaymentGroup;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.sync.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Set;


public class Transaction {

    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    private final int id;
    private final OffsetDateTime timestamp;
    private final Set<Ticket> tickets;

    public int getId() {
        return id;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public Set<Ticket> getTickets() {
        return tickets;
    }

    public Transaction(int id, OffsetDateTime timestamp, Set<Ticket> tickets) {
        this.id = id;
        this.timestamp = timestamp;
        this.tickets = tickets;
    }

    @Override
    public String toString() {
        return String.format("Transaction(%d, %s, %d tickets)", getId(), getTimestamp(), getTickets().size());
    }

    public static void create(PaymentGroup group, Offer offer,
                              SQLConnection connection,
                              Handler<AsyncResult<Transaction>> handler) {
        logger.debug("Creating transaction for payment group {} for offer {}", group, offer);
        OffsetDateTime now = OffsetDateTime.now();
        JsonArray params = new JsonArray()
                .add(offer.getId())
                .add(group.getId())
                .add(Time.toSql(now));
        connection.updateWithParams(
                "INSERT INTO Transactions (OfferID, PaymentGroupID, Timestamp) " +
                "VALUES (?, ?, ?);", params, Sync.fiberHandler(transactionRes -> {
                    if (transactionRes.failed()) {
                        logger.warn("Failed to insert new transaction", transactionRes.cause());
                        handler.handle(Future.failedFuture("Error adding transaction; is has likely already been paid"));
                        return;
                    }

                    UpdateResult result = transactionRes.result();
                    int transactionId = result.getKeys().getInteger(0);
                    Ticket.createFor(transactionId, group, offer, connection, ticketsRes -> {
                        if (ticketsRes.failed()) {
                            handler.handle(Future.failedFuture(ticketsRes.cause()));
                            return;
                        }

                        Set<Ticket> tickets = ticketsRes.result();
                        Transaction transaction = new Transaction(transactionId, now, tickets);
                        handler.handle(Future.succeededFuture(transaction));
                    });
                }));
    }
}
