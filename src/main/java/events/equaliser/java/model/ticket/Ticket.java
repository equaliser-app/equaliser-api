package events.equaliser.java.model.ticket;

import co.paralleluniverse.fibers.Suspendable;
import com.twilio.rest.api.v2010.account.Message;
import events.equaliser.java.model.event.Tier;
import events.equaliser.java.model.group.PaymentGroup;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Sms;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.sync.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a single ticket, granting a user admission to a fixture.
 */
public class Ticket {

    private static final Logger logger = LoggerFactory.getLogger(Ticket.class);

    private final int id;
    private final User user;
    private OffsetDateTime notificationSent;

    public int getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OffsetDateTime isNotificationSent() {
        return notificationSent;
    }

    public void setNotificationSent(OffsetDateTime notificationSent) {
        this.notificationSent = notificationSent;
    }

    public Ticket(int id, User user) {
        this.id = id;
        this.user = user;
    }

    private Ticket(int id, User user, OffsetDateTime notificationSent) {
        this(id, user);
        this.notificationSent = notificationSent; // N.B. can still be null - may not have been sent
    }

    @Override
    public String toString() {
        return String.format("Ticket(%d, %s)", getId(), getUser());
    }

    /**
     * Turn a JSON object into a ticket.
     *
     * @param json The JSON object with correct keys.
     * @return The Ticket representation of the object.
     */
    public static Ticket fromJsonObject(JsonObject json) {
        return new Ticket(
                json.getInteger("TicketID"),
                User.fromJsonObject(json),
                Time.parseOffsetDateTime(json.getString("TicketNotificationSent")));
    }

    @Suspendable
    static void createFor(int transactionId,
                          PaymentGroup group,
                          Offer offer,
                          SQLConnection connection,
                          Handler<AsyncResult<Set<Ticket>>> handler) {
        logger.debug("Creating tickets for payment group {} for transaction {}", group, transactionId);

        try {
            Set<Ticket> tickets = new HashSet<>();
            for (User user : group.getAttendees()) {
                    JsonArray params = new JsonArray().add(transactionId).add(user.getId());
                    UpdateResult result = Sync.awaitResult(h ->
                            connection.updateWithParams(
                                    "INSERT INTO Tickets (TransactionID, UserID) VALUES (?, ?);",
                                    params, h));
                    tickets.add(new Ticket(result.getKeys().getInteger(0), user));

            }
            logger.debug("Created {} tickets: {}", tickets.size(), tickets);
            handler.handle(Future.succeededFuture(tickets));
        } catch (VertxException e) {
            handler.handle(Future.failedFuture(e));
        }
    }

    private String getNotification(Tier tier) {
        return String.format(
                "Congratulations %s! You're going to see %s on %s. Your ticket is available in the Equaliser app.",
                getUser().getForename(),
                tier.getFixture().getSeries().getName(),
                Time.formatDatetime(tier.getFixture().getStart()));
    }

    public void sendNotification(Tier tier, SQLConnection connection, Handler<AsyncResult<Message>> handler) {
        Sms.send(getNotification(tier), getUser(), smsRes -> {
            if (smsRes.failed()) {
                handler.handle(Future.failedFuture(smsRes.cause()));
                return;
            }

            Message message = smsRes.result();
            JsonArray params = new JsonArray()
                    .add(Time.toSql(Time.toOffsetDateTime(message.getDateCreated())))
                    .add(getId());
            connection.updateWithParams(
                    "UPDATE Tickets SET NotificationSent = ? WHERE TicketID = ?;", params, updateRes ->
                            handler.handle(updateRes.succeeded() ?
                                    Future.succeededFuture(message) :
                                    Future.failedFuture(updateRes.cause())));
        });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Ticket ticket = (Ticket) o;

        return getId() == ticket.getId();
    }

    @Override
    public int hashCode() {
        return getId();
    }
}
