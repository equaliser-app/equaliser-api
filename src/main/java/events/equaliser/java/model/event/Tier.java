package events.equaliser.java.model.event;

import events.equaliser.java.model.group.Group;
import events.equaliser.java.util.Json;
import events.equaliser.java.verticles.PrimaryPoolVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A class of ticket available for a fixture.
 */
public class Tier {

    private final int id;
    private final String name;
    private final BigDecimal price;
    private final int availability;
    private final int fixtureId;
    private Fixture fixture;
    private Boolean isAvailable;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getFixtureId() {
        return fixtureId;
    }

    public Fixture getFixture() {
        return fixture;
    }

    public int getAvailability() {
        return availability;
    }

    public Boolean isAvailable() {
        return isAvailable;
    }

    private void setAvailable(Boolean available) {
        isAvailable = available;
    }

    private Tier(int id, String name, BigDecimal price, int availability, int fixtureId) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.availability = availability;
        this.fixtureId = fixtureId;
    }

    private Tier(int id, String name, BigDecimal price, int availability, Fixture fixture) {
        this(id, name, price, availability, fixture.getId());
        this.fixture = fixture;
    }

    @Override
    public String toString() {
        return String.format("Tier(%d, %s, %s)",
                getId(), getName(), getFixture() == null ? getFixtureId() : getFixture());
    }

    public void findAvailability(Handler<AsyncResult<Integer>> handler) {
        EventBus eb = Vertx.currentContext().owner().eventBus();
        eb.send(PrimaryPoolVerticle.PRIMARY_POOL_AVAILABILITY_ADDRESS,
                new JsonObject().put("tierId", getId()), res -> {
                    if (res.failed()) {
                        handler.handle(Future.failedFuture(res.cause()));
                        return;
                    }

                    Message<Object> message = res.result();
                    JsonObject result = (JsonObject)message.body();
                    Integer remaining = result.getInteger("remaining");
                    handler.handle(Future.succeededFuture(remaining));
                });
    }

    /**
     * Turn a JSON object into a tier.
     *
     * @param json The JSON object with correct keys.
     * @return The Tier representation of the object.
     */
    public static Tier fromJsonObject(JsonObject json) {
        return new Tier(json.getInteger("TierID"),
                json.getString("TierName"),
                new BigDecimal(json.getString("TierPrice")),
                json.getInteger("TierAvailability"),
                json.getInteger("FixtureID"));
    }

    private static void fromJsonObject(JsonObject json, SQLConnection connection, Handler<AsyncResult<Tier>> handler) {
        Tier tier = fromJsonObject(json);
        Fixture.retrieveFromId(tier.fixtureId, connection, res -> {
            if (res.failed()) {
                handler.handle(Future.failedFuture(res.cause()));
                return;
            }

            tier.fixture = res.result();
            handler.handle(Future.succeededFuture(tier));
        });
    }

    public static void retrieveById(int tierId,
                                    SQLConnection connection,
                                    Handler<AsyncResult<Tier>> handler) {
        JsonArray params = new JsonArray().add(tierId);
        connection.queryWithParams(
                "SELECT " +
                    "TierID, " +
                    "FixtureID, " +
                    "Name AS TierName, " +
                    "Price AS TierPrice, " +
                    "Availability AS TierAvailability, " +
                    "ReturnsPolicy AS TierReturnsPolicy " +
                "FROM Tiers " +
                "WHERE TierID = ?;",
                params, tierRes -> {
                    if (tierRes.failed()) {
                        handler.handle(Future.failedFuture(tierRes.cause()));
                        return;
                    }

                    ResultSet resultSet = tierRes.result();
                    if (resultSet.getNumRows() == 0) {
                        handler.handle(Future.failedFuture("No tier found with id " + tierId));
                        return;
                    }
                    JsonObject row = resultSet.getRows().get(0);
                    Tier.fromJsonObject(row, connection, fixtureRes -> {
                        if (fixtureRes.failed()) {
                            handler.handle(Future.failedFuture(fixtureRes.cause()));
                            return;
                        }

                        Tier tier = fixtureRes.result();
                        handler.handle(Future.succeededFuture(tier));
                    });
                });
    }

    static void retrieveByFixture(int fixtureId,
                                  SQLConnection connection,
                                  Handler<AsyncResult<List<Tier>>> handler) {
        JsonArray params = new JsonArray().add(fixtureId);
        connection.queryWithParams(
                "SELECT " +
                    "TierID, " +
                    "FixtureID, " +
                    "Name AS TierName, " +
                    "Price AS TierPrice, " +
                    "Availability AS TierAvailability, " +
                    "ReturnsPolicy AS TierReturnsPolicy " +
                "FROM Tiers " +
                "WHERE FixtureID = ?;",
                params, tiersRes -> {
                    if (tiersRes.succeeded()) {
                        ResultSet resultSet = tiersRes.result();
                        List<Tier> tiers = resultSet.getRows()
                                .stream()
                                .map(Tier::fromJsonObject)
                                .collect(Collectors.toList());
                        List<Integer> tierIds = tiers.stream()
                                .mapToInt(Tier::getId)
                                .boxed()
                                .collect(Collectors.toList());
                        EventBus eb = Vertx.currentContext().owner().eventBus();
                        eb.send(PrimaryPoolVerticle.PRIMARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS,
                                Json.toJsonArray(tierIds), reply -> {
                                    if (reply.failed()) {
                                        handler.handle(Future.failedFuture(reply.cause()));
                                        return;
                                    }

                                    Message<Object> message = reply.result();
                                    JsonObject json = (JsonObject)message.body();
                                    for (Tier tier : tiers) {
                                        tier.setAvailable(json.getInteger(Integer.toString(tier.getId())) > 0);
                                    }
                                    handler.handle(Future.succeededFuture(tiers));
                                });
                    }
                    else {
                        handler.handle(Future.failedFuture(tiersRes.cause()));
                    }
                });
    }

    static void retrieveBySeries(int seriesId,
                                 SQLConnection connection,
                                 Handler<AsyncResult<Map<Integer,List<Tier>>>> handler) {
        JsonArray params = new JsonArray().add(seriesId);
        connection.queryWithParams(
                "SELECT " +
                        "Fixtures.FixtureID, " +
                        "Tiers.TierID, " +
                        "Tiers.Name AS TierName, " +
                        "Tiers.Price AS TierPrice, " +
                        "Tiers.Availability AS TierAvailability, " +
                        "Tiers.ReturnsPolicy AS TierReturnsPolicy " +
                        "FROM Fixtures " +
                        "INNER JOIN Tiers " +
                        "ON Tiers.FixtureID = Fixtures.FixtureID " +
                        "WHERE Fixtures.SeriesID = ?;",
                params, tiers -> {
                    if (tiers.succeeded()) {
                        ResultSet resultSet = tiers.result();
                        if (resultSet.getNumRows() == 0) {
                            handler.handle(Future.failedFuture("No series found with id " + seriesId));
                            return;
                        }

                        List<Integer> tierIds = resultSet.getRows().stream()
                                .map(object -> object.getInteger("TierID"))
                                .collect(Collectors.toList());

                        EventBus eb = Vertx.currentContext().owner().eventBus();
                        eb.send(PrimaryPoolVerticle.PRIMARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS,
                                new JsonArray(tierIds), reply -> {
                                    if (reply.failed()) {
                                        handler.handle(Future.failedFuture(reply.cause()));
                                        return;
                                    }

                                    JsonObject map = (JsonObject) reply.result().body();
                                    Map<Integer, List<Tier>> fixtureTiers = new HashMap<>();
                                    for (JsonObject row : resultSet.getRows()) {
                                        int fixtureId = row.getInteger("FixtureID");
                                        if (!fixtureTiers.containsKey(fixtureId)) {
                                            fixtureTiers.put(fixtureId, new ArrayList<>());
                                        }
                                        Tier tier = fromJsonObject(row);
                                        tier.setAvailable(map.getInteger(Integer.toString(tier.getId())) > 0);
                                        fixtureTiers.get(fixtureId).add(tier);
                                    }
                                    handler.handle(Future.succeededFuture(fixtureTiers));
                                });
                    } else {
                        handler.handle(Future.failedFuture(tiers.cause()));
                    }
                });
    }

    public static void retrieveByGroup(Group group,
                                       SQLConnection connection,
                                       Handler<AsyncResult<List<Tier>>> handler) {
        JsonArray params = new JsonArray().add(group.getId());
        connection.queryWithParams(
                "SELECT " +
                    "Tiers.FixtureID, " +
                    "Tiers.TierID, " +
                    "Tiers.Name AS TierName, " +
                    "Tiers.Price AS TierPrice, " +
                    "Tiers.Availability AS TierAvailability, " +
                    "Tiers.ReturnsPolicy AS TierReturnsPolicy " +
                "FROM GroupTiers " +
                    "INNER JOIN Tiers " +
                        "ON Tiers.TierID = GroupTiers.TierID " +
                "WHERE GroupTiers.GroupID = ? " +
                "ORDER BY GroupTiers.Rank ASC;",
                params, tiersRes -> {
                    if (tiersRes.failed()) {
                        handler.handle(Future.failedFuture(tiersRes.cause()));
                        return;
                    }

                    ResultSet resultSet = tiersRes.result();
                    List<Integer> tierIds = resultSet.getRows().stream()
                            .map(object -> object.getInteger("TierID"))
                            .collect(Collectors.toList());

                    EventBus eb = Vertx.currentContext().owner().eventBus();
                    eb.send(PrimaryPoolVerticle.PRIMARY_POOL_AVAILABILITY_MULTIPLE_ADDRESS,
                            new JsonArray(tierIds), replyRes -> {
                                if (replyRes.failed()) {
                                    handler.handle(Future.failedFuture(replyRes.cause()));
                                    return;
                                }

                                JsonObject map = (JsonObject)replyRes.result().body();
                                List<Tier> tiers = new ArrayList<>();
                                for (JsonObject row : resultSet.getRows()) {
                                    Tier tier = fromJsonObject(row);
                                    tier.setAvailable(map.getInteger(Integer.toString(tier.getId())) > 0);
                                    tiers.add(tier);
                                }

                                handler.handle(Future.succeededFuture(tiers));
                            });
                });
    }
}
