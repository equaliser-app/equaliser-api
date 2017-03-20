package events.equaliser.java.model.group;

import co.paralleluniverse.fibers.Suspendable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import events.equaliser.java.model.ticket.Offer;
import events.equaliser.java.model.user.User;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.sync.Sync;

import java.util.*;


public class PaymentGroup {

    private final int id;
    private final User payee;
    private final Set<User> attendees;
    private final Status status;

    public enum Status {
        INHERIT("Nothing payment group specific; check group status"),
        EXPIRED("An offer was made to the group, but the payee responsible for this " +
                "payment group didn't complete checkout in time"), // final state
        COMPLETE("Offer made, payment received, tickets issued, enjoy the event");  // final state

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public int getId() {
        return id;
    }

    public User getPayee() {
        return payee;
    }

    public Set<User> getAttendees() {
        return attendees;
    }

    @JsonIgnore
    public int getSize() {
        return getAttendees().size();
    }

    public Status getStatus() {
        return status;
    }

    private PaymentGroup(int id, User payee, Set<User> attendees, Status status) {
        this.id = id;
        this.payee = payee;
        this.attendees = attendees;
        this.status = status;
    }

    private PaymentGroup(int id, User payee, Set<User> attendees) {
        this(id, payee, attendees, Status.INHERIT);
    }

    @Override
    public String toString() {
        return String.format("PaymentGroup(%d, %s, %d attendees)", getId(), getPayee(), getAttendees().size());
    }

    @Suspendable
    public static void create(Group parent,
                              Map<User, Set<User>> paymentGroups,
                              SQLConnection connection,
                              Handler<AsyncResult<Group>> handler) {
        List<PaymentGroup> groups = new ArrayList<>();
        for (Map.Entry<User, Set<User>> entry : paymentGroups.entrySet()) {
            JsonArray groupParams = new JsonArray()
                    .add(parent.getId())
                    .add(entry.getKey().getId());
            UpdateResult result = Sync.awaitResult(
                    h -> connection.updateWithParams(
                            "INSERT INTO PaymentGroups (GroupID, UserID) VALUES (?, ?);", groupParams, h));
            int paymentGroupId = result.getKeys().getInteger(0);

            for (User user : entry.getValue()) {
                JsonArray attendeeParams = new JsonArray()
                        .add(paymentGroupId)
                        .add(user.getId());
                result = Sync.awaitResult(
                        h -> connection.updateWithParams(
                                "INSERT INTO PaymentGroupAttendees (PaymentGroupID, UserID) VALUES (?, ?);",
                                attendeeParams, h));
            }

            groups.add(new PaymentGroup(paymentGroupId, entry.getKey(), entry.getValue()));
        }
        parent.setPaymentGroups(groups);
        handler.handle(Future.succeededFuture(parent));
    }

    /**
     * Get the status of a hypothetical payment group with information about the group's offer and the
     * payment group's transaction.
     *
     * @param offer The offer received by the parent group.
     * @param hasTransaction Whether a transaction exists for the payment group.
     * @return The status of the payment group.
     */
    private static Status getStatus(Offer offer, boolean hasTransaction) {
        if (hasTransaction) {
            // if we have a transaction, we must have accepted the offer in time
            return Status.COMPLETE;
        }

        if (offer != null && offer.hasExpired()) {
            // no transaction, so cannot be complete
            return Status.EXPIRED;
        }

        // either offer == null, so the entire group is waiting,
        // or !offer.hasExpired, so we're still in the offered state
        return Status.INHERIT;
    }

    static void retrieveByGroup(Group group,
                                Offer offer,
                                SQLConnection connection,
                                Handler<AsyncResult<List<PaymentGroup>>> handler) {
        JsonArray params = new JsonArray().add(group.getId());
        // first, get payment groups and their payees
        connection.queryWithParams(
                "SELECT " +
                    "PaymentGroups.PaymentGroupID, " +
                    "Transactions.TransactionID, " +
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
                "FROM PaymentGroups " +
                    "INNER JOIN Users " +
                        "ON Users.UserID = PaymentGroups.UserID " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                    "LEFT OUTER JOIN Transactions " +  // to ascertain status
                        "ON Transactions.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                "WHERE PaymentGroups.GroupID = ?;", params, payeesRes -> {
                    if (payeesRes.failed()) {
                        handler.handle(Future.failedFuture(payeesRes.cause()));
                        return;
                    }

                    // paymentGroupId:int -> payee:User
                    Map<Integer, User> payees = new HashMap<>();

                    // paymentGroupId:int -> hasTransaction:bool
                    Map<Integer, Boolean> transactions = new HashMap<>();

                    for (JsonObject row : payeesRes.result().getRows()) {
                        int paymentGroupId = row.getInteger("PaymentGroupID");
                        payees.put(paymentGroupId, User.fromJsonObject(row));
                        transactions.put(paymentGroupId, row.getInteger("TransactionID") != null);
                    }

                    // now get payment group attendees to match up
                    connection.queryWithParams(
                            "SELECT " +
                                "PaymentGroups.PaymentGroupID, " +
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
                            "FROM PaymentGroups " +
                                "INNER JOIN PaymentGroupAttendees " +
                                    "ON PaymentGroupAttendees.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                                "INNER JOIN Users " +
                                    "ON Users.UserID = PaymentGroupAttendees.UserID " +
                                "INNER JOIN Countries " +
                                    "ON Countries.CountryID = Users.CountryID " +
                            "WHERE PaymentGroups.GroupID = ?;", params, attendeesRes -> {
                                if (attendeesRes.failed()) {
                                    handler.handle(Future.failedFuture(attendeesRes.cause()));
                                    return;
                                }

                                // paymentGroupId:int -> attendees:Set<User>
                                Map<Integer, Set<User>> attendees = new HashMap<>();
                                for (JsonObject row : attendeesRes.result().getRows()) {
                                    int paymentGroupId = row.getInteger("PaymentGroupID");
                                    if (!attendees.containsKey(paymentGroupId)) {
                                        attendees.put(paymentGroupId, new HashSet<>());
                                    }
                                    attendees.get(paymentGroupId).add(User.fromJsonObject(row));
                                }

                                List<PaymentGroup> paymentGroups = new ArrayList<>();
                                for (Map.Entry<Integer, User> payee : payees.entrySet()) {
                                    paymentGroups.add(new PaymentGroup(
                                            payee.getKey(),
                                            payee.getValue(),
                                            attendees.get(payee.getKey()),
                                            getStatus(offer, transactions.get(payee.getKey()))));
                                }
                                handler.handle(Future.succeededFuture(paymentGroups));
                            });
                });
    }
}
