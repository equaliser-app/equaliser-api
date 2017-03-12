package events.equaliser.java.model.event;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Tier {

    private final int id;
    private final String name;
    private final BigDecimal price;
    private final int availability;
    private int remaining;

    private Tier(int id, String name, BigDecimal price, int availability) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.availability = availability;
    }

    /**
     * Turn a JSON object into a tier.
     *
     * @param json The JSON object with correct keys.
     * @return The Tier representation of the object.
     */
    private static Tier fromJsonObject(JsonObject json) {
        return new Tier(json.getInteger("TierID"),
                json.getString("TierName"),
                new BigDecimal(json.getString("TierPrice")),
                json.getInteger("TierAvailability"));
    }

    static void retrieveFromSeries(int seriesId,
                                   SQLConnection connection,
                                   Handler<AsyncResult<Map<Integer,List<Tier>>>> result) {
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
                            result.handle(Future.failedFuture("No series found with id " + seriesId));
                        }
                        else {
                            Map<Integer, List<Tier>> fixtureTiers = new HashMap<>();
                            for (JsonObject row : resultSet.getRows()) {
                                int fixtureId = row.getInteger("FixtureID");
                                if (!fixtureTiers.containsKey(fixtureId)) {
                                    fixtureTiers.put(fixtureId, new ArrayList<>());
                                }
                                fixtureTiers.get(fixtureId).add(Tier.fromJsonObject(row));
                            }
                            result.handle(Future.succeededFuture(fixtureTiers));
                        }
                    }
                    else {
                        result.handle(Future.failedFuture(tiers.cause()));
                    }
                });
    }
}
