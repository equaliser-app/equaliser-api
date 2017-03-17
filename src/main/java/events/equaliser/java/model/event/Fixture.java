package events.equaliser.java.model.event;

import events.equaliser.java.util.Time;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Fixture {
    private final int id;
    private final BareSeries series;
    private final OffsetDateTime start;
    private final OffsetDateTime finish;
    private final Venue venue;
    private final List<Tier> tiers;

    public int getId() {
        return id;
    }

    public BareSeries getSeries() {
        return series;
    }

    public OffsetDateTime getStart() {
        return start;
    }

    public OffsetDateTime getFinish() {
        return finish;
    }

    public Venue getVenue() {
        return venue;
    }

    public List<Tier> getTiers() {
        return tiers;
    }

    public Fixture(int id, BareSeries series, OffsetDateTime start, OffsetDateTime finish, Venue venue, List<Tier> tiers) {
        this.id = id;
        this.series = series;
        this.start = start;
        this.finish = finish;
        this.venue = venue;
        this.tiers = tiers;
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<Fixture>> handler) {
        Tier.retrieveByFixture(id, connection, tiersRes -> {
            if (tiersRes.failed()) {
                handler.handle(Future.failedFuture(tiersRes.cause()));
                return;
            }

            List<Tier> tiers = tiersRes.result();
            JsonArray params = new JsonArray().add(id);
            connection.queryWithParams(
                    "SELECT " +
                        "Fixtures.SeriesID, " +
                        "Fixtures.Start AS FixtureStart, " +
                        "Fixtures.Finish AS FixtureFinish, " +
                        "Venues.VenueID, " +
                        "Venues.Name AS VenueName, " +
                        "Venues.Address AS VenueAddress, " +
                        "Venues.Postcode AS VenuePostcode, " +
                        "Venues.AreaCode AS VenueAreaCode, " +
                        "Venues.Phone AS VenuePhone, " +
                        "X(Venues.Location) AS VenueLocationLatitude, " +
                        "Y(Venues.Location) AS VenueLocationLongitude, " +
                        "Countries.CountryID, " +
                        "Countries.Name AS CountryName, " +
                        "Countries.Abbreviation AS CountryAbbreviation, " +
                        "Countries.CallingCode AS CountryCallingCode " +
                    "FROM Fixtures " +
                        "INNER JOIN Venues " +
                            "ON Venues.VenueID = Fixtures.VenueID " +
                        "INNER JOIN Countries " +
                            "ON Countries.CountryID = Venues.CountryID " +
                    "WHERE Fixtures.FixtureID = ?;",
                    params, fixtureRes -> {
                        if (fixtureRes.failed()) {
                            handler.handle(Future.failedFuture(fixtureRes.cause()));
                            return;
                        }

                        ResultSet rows = fixtureRes.result();
                        if (rows.getNumRows() == 0) {
                            handler.handle(Future.failedFuture("No fixture found with id " + id));
                            return;
                        }

                        JsonObject row = rows.getRows().get(0);
                        int seriesId = row.getInteger("SeriesID");
                        BareSeries.retrieveFromId(seriesId, connection, seriesRes -> {
                            if (seriesRes.failed()) {
                                handler.handle(Future.failedFuture(
                                        "No series found with id " + seriesId + " for fixture"));
                                return;
                            }

                            BareSeries series = seriesRes.result();
                            OffsetDateTime start = Time.parseOffsetDateTime(row.getString("FixtureStart"));
                            OffsetDateTime finish = Time.parseOffsetDateTime(row.getString("FixtureFinish"));
                            Venue venue = Venue.fromJsonObject(row);
                            Fixture fixture = new Fixture(id, series, start, finish, venue, tiers);
                            handler.handle(Future.succeededFuture(fixture));
                        });
                    });
        });
    }

    static void retrieveFromSeries(BareSeries series,
                                   SQLConnection connection,
                                   Handler<AsyncResult<List<Fixture>>> handler) {
        Tier.retrieveBySeries(series.getId(), connection, tiersResult -> {
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
                            "X(Venues.Location) AS VenueLocationLatitude, " +
                            "Y(Venues.Location) AS VenueLocationLongitude, " +
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
                                    handler.handle(Future.failedFuture("No series found with id " + series.getId()));
                                } else {
                                    List<Fixture> fixtures = new ArrayList<>();
                                    for (JsonObject row : resultSet.getRows()) {
                                        OffsetDateTime start = Time.parseOffsetDateTime(row.getString("FixtureStart"));
                                        OffsetDateTime finish = Time.parseOffsetDateTime(row.getString("FixtureFinish"));
                                        Venue venue = Venue.fromJsonObject(row);
                                        int fixtureId = row.getInteger("FixtureID");
                                        List<Tier> tiers = fixtureTiers.get(fixtureId);
                                        fixtures.add(new Fixture(fixtureId, series, start, finish, venue, tiers));
                                    }
                                    handler.handle(Future.succeededFuture(fixtures));
                                }
                            } else {
                                handler.handle(Future.failedFuture(fixturesResult.cause()));
                            }
                        });
            } else {
                handler.handle(Future.failedFuture(tiersResult.cause()));
            }
        });
    }
}
