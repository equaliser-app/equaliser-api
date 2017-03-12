package events.equaliser.java.model.geography;

import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.Config;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a country, e.g. the United Kingdom.
 */
public class Country {

    /**
     * The unique identifier for this country in the database.
     */
    private final int id;

    /**
     * e.g. "United Kingdom"
     */
    private final String name;

    /**
     * e.g. "UK"
     */
    private final String abbreviation;

    /**
     * e.g. "44"
     */
    private final String callingCode;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    @JsonProperty("calling_code")
    public String getCallingCode() {
        return callingCode;
    }

    @JsonProperty("flag_url")
    public String flagUrl() {
        return String.format("%s/countries/%s.png", Config.STATIC_CONTENT_URL, getAbbreviation().toLowerCase());
    }

    /**
     * Initialise a new country object.
     *
     * @param id The unique identifier for this country in the database.
     * @param name The name of the country, e.g. "United Kingdom".
     * @param abbreviation The country's abbreviation, e.g. "UK".
     * @param callingCode The country's calling code, e.g. "44".
     */
    Country(int id,
            String name,
            String abbreviation,
            String callingCode) {
        this.id = id;
        this.name = name;
        this.abbreviation = abbreviation;
        this.callingCode = callingCode;
    }

    /**
     * Turn a JSON object into a country.
     *
     * @param json The JSON object with correct keys.
     * @return The Country representation of the object.
     */
    public static Country fromJsonObject(JsonObject json) {
        return new Country(json.getInteger("CountryID"),
                json.getString("CountryName"),
                json.getString("CountryAbbreviation"),
                json.getString("CountryCallingCode"));
    }

    /**
     * Retrieve a list of all countries.
     *
     * @param connection The connection to use to query the database.
     * @param result The result handler.
     */
    public static void retrieveAll(SQLConnection connection, Handler<AsyncResult<List<Country>>> result) {
        connection.query(
                "SELECT CountryID, Name AS CountryName, Abbreviation AS CountryAbbreviation, " +
                "CallingCode AS CountryCallingCode " +
                "FROM Countries " +
                "ORDER BY CountryName ASC;", query -> {
            if (query.succeeded()) {
                List<Country> countries = query.result()
                        .getRows()
                        .stream()
                        .map(Country::fromJsonObject)
                        .collect(Collectors.toList());
                result.handle(Future.succeededFuture(countries));
            }
            else {
                result.handle(Future.failedFuture(query.cause()));
            }
        });
    }

    /**
     * Get a string representation of this country.
     *
     * @return The country as a string.
     */
    public String toString() {
        return String.format("Country(%s, %s)", name, abbreviation);
    }
}
