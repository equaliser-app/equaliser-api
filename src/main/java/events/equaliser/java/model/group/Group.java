package events.equaliser.java.model.group;

import co.paralleluniverse.fibers.Suspendable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import events.equaliser.java.model.event.Fixture;
import events.equaliser.java.model.event.Tier;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public class Group {

    private static final Logger logger = LoggerFactory.getLogger(Group.class);

    private final int id;
    private final User leader;
    private final Fixture fixture;
    private final OffsetDateTime created;
    private final Status status;
    private List<PaymentGroup> paymentGroups;
    private List<Tier> tiers; // the group is/was waiting for
    private Offer offer;

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

    public Fixture getFixture() {
        return fixture;
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

    public List<Tier> getTiers() {
        return tiers;
    }

    public void setTiers(List<Tier> tiers) {
        this.tiers = tiers;
    }

    @JsonManagedReference
    public Offer getOffer() {
        return offer;
    }

    public void setOffer(Offer offer) {
        this.offer = offer;
    }

    public int getSize() {
        if (getPaymentGroups() == null) {
            return 0;
        }

        return getPaymentGroups().stream().mapToInt(PaymentGroup::getSize).sum();
    }

    private Group(int id, User leader, Fixture fixture, OffsetDateTime created) {
        this(id, leader, fixture, created, Status.WAITING);
    }

    private Group(int id, User leader, Fixture fixture, OffsetDateTime created, Status status) {
        this.id = id;
        this.leader = leader;
        this.fixture = fixture;
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
    public static Group fromJsonObject(JsonObject json, Fixture fixture) {
        return new Group(
                json.getInteger("GroupID"),
                User.fromJsonObject(json),
                fixture,
                Time.parseOffsetDateTime(json.getString("GroupCreated")),
                json.getInteger("OfferID") == null ? Status.WAITING : Status.OFFER);
    }

    public static void create(User leader, Fixture fixture,
                              SQLConnection connection,
                              Handler<AsyncResult<Group>> handler) {
        OffsetDateTime created = OffsetDateTime.now();
        JsonArray params = new JsonArray()
                .add(leader.getId())
                .add(fixture.getId())
                .add(Time.toSql(created));
        connection.updateWithParams(
                "INSERT INTO Groups (UserID, FixtureID, Created) " +
                "VALUES (?, ?, ?);",
                params, res -> {
                    if (res.failed()) {
                        handler.handle(Future.failedFuture(res.cause()));
                        return;
                    }

                    UpdateResult result = res.result();
                    int id = result.getKeys().getInteger(0);
                    Group group = new Group(id, leader, fixture, created);
                    handler.handle(Future.succeededFuture(group));
                });
    }

    /**
     *
     * @param priorities tierId -> rank, where rank 1 is highest. Do not have to be continuous numbers.
     * @param connection
     * @param handler
     */
    public void setTiers(Map<Integer, Integer> priorities,
                         SQLConnection connection,
                         Handler<AsyncResult<Void>> handler) {
        logger.debug("New priorities: {}", priorities);

        if (priorities.isEmpty()) {
            handler.handle(Future.failedFuture("At least one tier must be selected"));
            return;
        }

        // TODO check the tiers are all for Groups.FixtureID
        connection.setAutoCommit(false, autoCommitFalseRes -> {
            if (autoCommitFalseRes.failed()) {
                handler.handle(Future.failedFuture(autoCommitFalseRes.cause()));
                return;
            }

            connection.updateWithParams(
                    "DELETE FROM GroupTiers WHERE GroupID = ?;",
                    new JsonArray().add(getId()), deleteRes -> {
                        if (deleteRes.failed()) {
                            handler.handle(Future.failedFuture(deleteRes.cause()));
                            return;
                        }

                        StringBuilder tiersQuery = new StringBuilder(
                                "INSERT INTO GroupTiers (GroupID, TierID, Rank) " +
                                        "VALUES ");
                        int i = 0;
                        for (Map.Entry<Integer, Integer> entry : priorities.entrySet()) {
                            tiersQuery.append(
                                    String.format("(%d, %d, %d)", getId(), entry.getKey(), entry.getValue()));
                            if (i != priorities.size() - 1) {
                                tiersQuery.append(',');
                            }
                            i++;
                        }
                        String tiersStatement = tiersQuery.toString();

                        // TODO please let there be some form of chaining handlers that's more elegant than this...
                        connection.update(tiersStatement, insertRes -> {
                            if (insertRes.failed()) {
                                handler.handle(Future.failedFuture(insertRes.cause()));
                                return;
                            }

                            connection.commit(commitRes -> {
                                if (commitRes.failed()) {
                                    handler.handle(Future.failedFuture(commitRes.cause()));
                                    return;
                                }

                                connection.setAutoCommit(true, autoCommitTrueRes -> {
                                    if (autoCommitFalseRes.failed()) {
                                        handler.handle(Future.failedFuture(autoCommitFalseRes.cause()));
                                        return;
                                    }

                                    handler.handle(Future.succeededFuture());
                                });
                            });
                        });
                    });
        });
    }

    public static void retrieveById(int id,
                                    SQLConnection connection,
                                    Handler<AsyncResult<Group>> handler) {
        JsonArray params = new JsonArray().add(id);
        connection.queryWithParams(
                "SELECT " +
                    "Groups.GroupID, " +
                    "Groups.FixtureID, " +
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
                    Fixture.retrieveFromId(row.getInteger("FixtureID"), connection, fixtureRes -> {
                        if (fixtureRes.failed()) {
                            handler.handle(Future.failedFuture(fixtureRes.cause()));
                            return;
                        }

                        Fixture fixture = fixtureRes.result();
                        Group group = fromJsonObject(row, fixture);
                        Tier.retrieveByGroup(group, connection, tiersRes -> {
                            if (tiersRes.failed()) {
                                handler.handle(Future.failedFuture(tiersRes.cause()));
                                return;
                            }
                            List<Tier> tiers = tiersRes.result();
                            logger.debug("Retrieved {} tiers for group {}", tiers.size(), group.getId());
                            group.setTiers(tiers);
                            Offer.retrieveByGroup(group, connection, offerRes -> {
                                if (offerRes.failed()) {
                                    handler.handle(Future.failedFuture(offerRes.cause()));
                                    return;
                                }

                                Optional<Offer> offerOptional = offerRes.result();
                                offerOptional.ifPresent(group::setOffer);

                                PaymentGroup.retrieveByGroup(group, offerOptional.orElse(null),
                                        connection, groupsRes -> {
                                    if (groupsRes.failed()) {
                                        handler.handle(Future.failedFuture(groupRes.cause()));
                                        return;
                                    }

                                    group.setPaymentGroups(groupsRes.result());
                                    handler.handle(Future.succeededFuture(group));
                                });
                            });
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
                "SELECT DISTINCT Groups.GroupID, Groups.Created " +
                "FROM PaymentGroupAttendees " +
                    "INNER JOIN PaymentGroups " +
                        "ON PaymentGroups.PaymentGroupID = PaymentGroupAttendees.PaymentGroupID " +
                    "INNER JOIN Groups " +
                        "ON Groups.GroupID = PaymentGroups.GroupID " +
                "WHERE Groups.UserID = ? " +
                    "OR PaymentGroups.UserID = ? " +
                    "OR PaymentGroupAttendees.UserID = ? " +
                "ORDER BY Groups.Created DESC;", params, Sync.fiberHandler(groupsRes -> {
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
