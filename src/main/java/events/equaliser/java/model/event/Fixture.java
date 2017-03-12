package events.equaliser.java.model.event;

import events.equaliser.java.model.geography.Venue;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Fixture {
    private final BareSeries series;
    private final OffsetDateTime start;
    private final OffsetDateTime finish;
    private final Venue venue;
    private final List<Tier> tiers;

    public Fixture(BareSeries series, OffsetDateTime start, OffsetDateTime finish, Venue venue, List<Tier> tiers) {
        this.series = series;
        this.start = start;
        this.finish = finish;
        this.venue = venue;
        this.tiers = tiers;
    }

    public static void retrieveFromSeries(BareSeries series,
                                          SQLConnection connection,
                                          Handler<AsyncResult<List<Fixture>>> result) {
        Tier.retrieveFromSeries(series.getId(), connection, tiersResult -> {
            if (tiersResult.succeeded()) {
                Map<Integer, List<Tier>> fixtureTiers = tiersResult.result();
                JsonArray params = new JsonArray().add(series.getId());
                connection.queryWithParams(
                        "SELECT " +
                                "Fixtures.FixtureID, " +
                                "Fixtures.Start AS FixtureStart, " +
                                "Fixtures.Finish AS FixtureFinish, " +
                                "Venues.VenueID, " +
                                "Venues.Name AS VenueName, " +
                                "Venues.Address AS VenueAddress, " +
                                "Venues.Postcode AS VenuePostcode, " +
                                "Venues.AreaCode AS VenueAreaCode, " +
                                "Venues.Phone AS VenuePhone, " +
                                "Venues.Location AS VenueLocation, " +
                                "Countries.CountryID, " +
                                "Countries.Name AS CountryName, " +
                                "Countries.Abbreviation AS CountryAbbreviation, " +
                                "Countries.CallingCode AS CountryCallingCode " +
                        "FROM Fixtures " +
                            "INNER JOIN Venues " +
                                "ON Venues.VenueID = Fixtures.VenueID " +
                            "INNER JOIN Countries " +
                                "ON Countries.CountryID = Venues.CountryID " +
                        "WHERE Fixtures.SeriesID = ?;",
                        params, fixturesResult -> {
                            if (fixturesResult.succeeded()) {
                                ResultSet resultSet = fixturesResult.result();
                                if (resultSet.getNumRows() == 0) {
                                    result.handle(Future.failedFuture("No series found with id " + series.getId()));
                                }
                                else {
                                    List<Fixture> fixtures = new ArrayList<>();
                                    for (JsonObject row : resultSet.getRows()) {
                                        OffsetDateTime start = row.getInstant("FixtureStart").atOffset(ZoneOffset.UTC);
                                        OffsetDateTime finish = row.getInstant("FixtureFinish").atOffset(ZoneOffset.UTC);
                                        Venue venue = Venue.fromJsonObject(row);
                                        List<Tier> tiers = fixtureTiers.get(row.getInteger("FixtureID"));
                                        fixtures.add(new Fixture(series, start, finish, venue, tiers));
                                    }
                                    result.handle(Future.succeededFuture(fixtures));
                                }
                            }
                            else {
                                result.handle(Future.failedFuture(fixturesResult.cause()));
                            }
                        });
                    }
                    else {
                        result.handle(Future.failedFuture(tiersResult.cause()));
                    }
                });
    }
}
