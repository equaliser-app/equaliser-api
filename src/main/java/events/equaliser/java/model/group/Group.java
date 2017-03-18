package events.equaliser.java.model.group;

import co.paralleluniverse.fibers.Suspendable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import events.equaliser.java.model.ticket.Offer;
import events.equaliser.java.model.user.User;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public class Group {

    private final int id;
    private final User leader;
    private final OffsetDateTime created;
    private final Status status;
    private List<PaymentGroup> paymentGroups;

    public enum Status {
        WAITING("In the waiting list for requested tiers; these tiers can still be modified"),
        OFFER("An offer has been made; payees have a couple of minutes to accept before it expires");  // final state

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

    public User getLeader() {
        return leader;
    }

    public OffsetDateTime getCreated() {
        return created;
    }

    public Status getStatus() {
        return status;
    }

    public List<PaymentGroup> getPaymentGroups() {
        return paymentGroups;
    }

    public Optional<PaymentGroup> getPaymentGroup(User user) {
        for (PaymentGroup group : getPaymentGroups()) {
            if (group.getPayee().equals(user)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    void setPaymentGroups(List<PaymentGroup> paymentGroups) {
        this.paymentGroups = paymentGroups;
    }

    @JsonIgnore
    public int getSize() {
        if (getPaymentGroups() == null) {
            return 0;
        }

        return getPaymentGroups().stream().mapToInt(PaymentGroup::getSize).sum();
    }

    private Group(int id, User leader, OffsetDateTime created) {
        this(id, leader, created, Status.WAITING);
    }

    private Group(int id, User leader, OffsetDateTime created, Status status) {
        this.id = id;
        this.leader = leader;
        this.created = created;
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("Group(%d, leader: %s, %d payment group(s))",
                getId(), getLeader(), getPaymentGroups() == null ? -1 : getPaymentGroups().size());
    }

    /**
     * Turn a JSON object into a group.
     *
     * @param json The JSON object with correct keys.
     * @return The Group representation of the object.
     */
    public static Group fromJsonObject(JsonObject json) {
        return new Group(
                json.getInteger("GroupID"),
                User.fromJsonObject(json),
                Time.parseOffsetDateTime(json.getString("GroupCreated")),
                json.getInteger("OfferID") == null ? Status.WAITING : Status.OFFER);
    }

    public static void create(User leader,
                              SQLConnection connection,
                              Handler<AsyncResult<Group>> handler) {
        OffsetDateTime created = OffsetDateTime.now();
        JsonArray params = new JsonArray()
                .add(leader.getId())
                .add(Time.toSql(created));
        connection.updateWithParams(
                "INSERT INTO Groups (UserID, Created) " +
                "VALUES (?, ?);",
                params, res -> {
                    if (res.failed()) {
                        handler.handle(Future.failedFuture(res.cause()));
                        return;
                    }

                    UpdateResult result = res.result();
                    int id = result.getKeys().getInteger(0);
                    Group group = new Group(id, leader, created);
                    handler.handle(Future.succeededFuture(group));
                });
    }

    /**
     *
     * @param priorities tierId -> rank, where rank 1 is highest. Do not have to be continuous numbers.
     * @param connection
     * @param handler
     */
    public void insertAdditionalTiers(Map<Integer, Integer> priorities,
                                      SQLConnection connection,
                                      Handler<AsyncResult<Void>> handler) {
        // TODO check the tiers are all for the same fixture (we don't know which one yet)

        StringBuilder tiersQuery = new StringBuilder(
                "INSERT INTO GroupTiers (GroupID, TierID, Rank) " +
                        "VALUES ");
        int i = 0;
        for (Map.Entry<Integer, Integer> entry : priorities.entrySet()) {
            tiersQuery.append(String.format("(%d, %d, %d)", getId(), entry.getKey(), entry.getValue()));
            if (i != priorities.size() - 1) {
                tiersQuery.append(',');
            }
            i++;
        }
        String tiersStatement = tiersQuery.toString();
        connection.update(tiersStatement, res ->
                handler.handle(res.succeeded() ? Future.succeededFuture() : Future.failedFuture(res.cause())));
    }

    public static void retrieveById(int id,
                                    SQLConnection connection,
                                    Handler<AsyncResult<Group>> handler) {
        JsonArray params = new JsonArray().add(id);
        connection.queryWithParams(
                "SELECT " +
                    "Groups.GroupID, " +
                    "Groups.Created AS GroupCreated, " +
                    "Offers.OfferID, " +  // so we can easily get the status
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
                "FROM Groups " +
                    "INNER JOIN Users " +
                        "ON Users.UserID = Groups.UserID " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                    "LEFT OUTER JOIN Offers " +
                        "ON Offers.GroupID = Groups.GroupID " +
                "WHERE Groups.GroupID = ?;", params, groupRes -> {
                    if (groupRes.failed()) {
                        handler.handle(Future.failedFuture(groupRes.cause()));
                        return;
                    }

                    ResultSet set = groupRes.result();
                    if (set.getNumRows() == 0) {
                        handler.handle(Future.failedFuture("No group found with id " + id));
                        return;
                    }

                    JsonObject row = set.getRows().get(0);
                    Group group = fromJsonObject(row);
                    Offer.retrieveByGroup(group, connection, offerRes -> {
                        if (offerRes.failed()) {
                            handler.handle(Future.failedFuture(offerRes.cause()));
                            return;
                        }

                        Optional<Offer> offerOptional = offerRes.result();
                        if (!offerOptional.isPresent()) {
                            handler.handle(Future.failedFuture("No offer has been made to the group"));
                            return;
                        }

                        Offer offer = offerOptional.get();
                        PaymentGroup.retrieveByGroup(group, offer, connection, groupsRes -> {
                            if (groupsRes.failed()) {
                                handler.handle(Future.failedFuture(groupRes.cause()));
                                return;
                            }

                            group.setPaymentGroups(groupsRes.result());
                            handler.handle(Future.succeededFuture(group));
                        });
                    });
                });
    }

    @Suspendable
    public static void retrieveByUser(User user,
                                      SQLConnection connection,
                                      Handler<AsyncResult<List<Group>>> handler) {
        JsonArray params = new JsonArray().add(user.getId()).add(user.getId()).add(user.getId());
        connection.queryWithParams(
                "SELECT Groups.GroupID " +
                "FROM PaymentGroupAttendees " +
                    "INNER JOIN PaymentGroups " +
                        "ON PaymentGroups.PaymentGroupID = PaymentGroupAttendees.PaymentGroupID " +
                    "INNER JOIN Groups " +
                        "ON Groups.GroupID = PaymentGroups.GroupID " +
                "WHERE Groups.UserID = ? " +
                    "OR PaymentGroups.UserID = ? " +
                    "OR PaymentGroupAttendees.UserID = ?;", params, Sync.fiberHandler(groupsRes -> {
                    if (groupsRes.failed()) {
                        handler.handle(Future.failedFuture(groupsRes.cause()));
                        return;
                    }

                    // TODO do asynchronously
                    ResultSet resultSet = groupsRes.result();
                    List<Group> groups = new ArrayList<>();
                    for (JsonObject row : resultSet.getRows()) {
                        int groupId = row.getInteger("GroupID");
                        Group group = Sync.awaitResult(h -> retrieveById(groupId, connection, h));
                        groups.add(group);
                    }
                    handler.handle(Future.succeededFuture(groups));
                }));
    }
}
