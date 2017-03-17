package events.equaliser.java.model.ticket;

import co.paralleluniverse.fibers.Suspendable;
import com.twilio.rest.api.v2010.account.Message;
import events.equaliser.java.model.event.Tier;
import events.equaliser.java.model.group.Group;
import events.equaliser.java.model.group.PaymentGroup;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Sms;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.sync.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Represents an offer to an group for tickets for a tier.
 */
public class Offer {

    private static final Logger logger = LoggerFactory.getLogger(Offer.class);

    private static final int OFFER_EXPIRY_MINUTES = 10;

    private final int id;
    private final Group group;
    private final Tier tier;
    private final OffsetDateTime timestamp;
    private final OffsetDateTime expires;

    public int getId() {
        return id;
    }

    public Group getGroup() {
        return group;
    }

    public Tier getTier() {
        return tier;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public OffsetDateTime getExpires() {
        return expires;
    }

    public boolean hasExpired() {
        return getExpires().isBefore(OffsetDateTime.now());
    }

    public Offer(int id, Group group, Tier tier) {
        OffsetDateTime now = OffsetDateTime.now();
        this.id = id;
        this.group = group;
        this.tier = tier;
        this.timestamp = now;
        this.expires = now.plusMinutes(OFFER_EXPIRY_MINUTES);
    }

    private Offer(int id, Group group, Tier tier, OffsetDateTime timestamp, OffsetDateTime expires) {
        this.id = id;
        this.group = group;
        this.tier = tier;
        this.timestamp = timestamp;
        this.expires = expires;
    }

    @Override
    public String toString() {
        return String.format("Offer(%d, %s, %s)", getId(), getGroup(), getTier());
    }

    /**
     * Turn a JSON object into an offer.
     *
     * @param json The JSON object with correct keys.
     * @param group The group this offer is for.
     * @return The Offer representation of the object.
     */
    private static Offer fromJsonObject(JsonObject json, Group group) {
        return new Offer(json.getInteger("OfferID"),
                group,
                Tier.fromJsonObject(json),
                Time.parseOffsetDateTime(json.getString("OfferTimestamp")),
                Time.parseOffsetDateTime(json.getString("OfferExpires")));
    }

    public static void create(Group group, Tier tier,
                              SQLConnection connection,
                              Handler<AsyncResult<Offer>> handler) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = now.plusMinutes(OFFER_EXPIRY_MINUTES);
        JsonArray params = new JsonArray()
                .add(group.getId())
                .add(tier.getId())
                .add(Time.toSql(now))
                .add(Time.toSql(expires));
        connection.updateWithParams(
                "INSERT INTO Offers (GroupID, TierID, Timestamp, Expires) " +
                "VALUES (?, ?, ?, ?);",
                params, insertRes -> {
                    if (insertRes.failed()) {
                        handler.handle(Future.failedFuture(insertRes.cause()));
                        return;
                    }

                    UpdateResult update = insertRes.result();
                    int offerId = update.getKeys().getInteger(0);
                    Offer offer = new Offer(offerId, group, tier, now, expires);
                    handler.handle(Future.succeededFuture(offer));
                });
    }

    public static void retrieveByGroup(Group group,
                                       SQLConnection connection,
                                       Handler<AsyncResult<Optional<Offer>>> handler) {
        JsonArray params = new JsonArray().add(group.getId());
        connection.queryWithParams(
                "SELECT " +
                    "Offers.OfferID, " +
                    "Offers.Timestamp AS OfferTimestamp, " +
                    "Offers.Expires AS OfferExpires, " +
                    "Tiers.TierID, " +
                    "Fixtures.FixtureID, " +
                    "Tiers.TierID, " +
                    "Tiers.Name AS TierName, " +
                    "Tiers.Price AS TierPrice, " +
                    "Tiers.Availability AS TierAvailability, " +
                    "Tiers.ReturnsPolicy AS TierReturnsPolicy " +
                "FROM Offers " +
                    "INNER JOIN Tiers " +
                        "ON Tiers.TierID = Offers.TierID " +
                    "INNER JOIN Fixtures " +
                        "ON Fixtures.FixtureID = Tiers.FixtureID " +
                "WHERE Offers.GroupID = ?;", params, offerRes -> {
                    if (offerRes.failed()) {
                        handler.handle(Future.failedFuture(offerRes.cause()));
                        return;
                    }

                    ResultSet set = offerRes.result();
                    if (set.getNumRows() == 0) {
                        // no offer for group
                        handler.handle(Future.succeededFuture(Optional.empty()));
                        return;
                    }

                    Offer offer = fromJsonObject(set.getRows().get(0), group);
                    handler.handle(Future.succeededFuture(Optional.of(offer)));
                });
    }

    /**
     * Send notifications for an offer.
     * N.B. As this is synchronous, it should only be used in a SyncVerticle.
     */
    @Suspendable
    public void sendNotificationsSync(SQLConnection connection) {
        logger.debug("Sending notifications for {} payment group(s)", getGroup().getPaymentGroups().size());
        for (PaymentGroup group : getGroup().getPaymentGroups()) {
            String message = getMessage(group.getPayee());
            Message sent = Sync.awaitResult(h -> Sms.send(message, group.getPayee(), h));
            Void object = Sync.awaitResult(h -> insertNotification(group.getPayee(), sent, connection, h));
        }
    }

    private void insertNotification(User user, Message message,
                                    SQLConnection connection, Handler<AsyncResult<Void>> handler) {
        JsonArray params = new JsonArray()
                .add(getId())
                .add(user.getId())
                .add(Time.toSql(Time.toOffsetDateTime(message.getDateCreated())));
        connection.updateWithParams(
                "INSERT INTO OfferNotifications (OfferID, UserID, Timestamp) " +
                "VALUES (?, ?, ?);", params, res -> handler.handle(res.succeeded() ?
                        Future.succeededFuture() :
                        Future.failedFuture(res.cause())));
    }

    private String getMessage(User user) {
        return String.format(
                "Congratulations %s! Your group has reached the front of the waiting list for %s on %s at %s. " +
                "You have until %s to complete your transaction.",
                user.getForename(),
                getTier().getFixture().getSeries().getName(),
                Time.formatDatetime(getTier().getFixture().getStart()),
                getTier().getFixture().getVenue().getName(),
                Time.formatDatetime(getExpires()));
    }
}
