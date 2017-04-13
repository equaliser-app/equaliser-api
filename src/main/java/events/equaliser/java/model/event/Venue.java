package events.equaliser.java.model.event;

import events.equaliser.java.model.geography.Coordinates;
import events.equaliser.java.model.geography.Country;
import io.vertx.core.json.JsonObject;

import java.awt.Point;

/**
 * A location where a fixture happens.
 */
public class Venue {

    private final int id;
    private final Country country;
    private final String name;
    private final String address;
    private final String postcode;
    private final String areaCode;
    private final String phone;
    private final Coordinates location;

    public int getId() {
        return id;
    }

    public Country getCountry() {
        return country;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getPostcode() {
        return postcode;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public String getPhone() {
        return phone;
    }

    public Coordinates getLocation() {
        return location;
    }

    private Venue(int id, Country country, String name, String address, String postcode, String areaCode, String phone,
                  Coordinates location) {
        this.id = id;
        this.country = country;
        this.name = name;
        this.address = address;
        this.postcode = postcode;
        this.areaCode = areaCode;
        this.phone = phone;
        this.location = location;
    }

    @Override
    public String toString() {
        return String.format("Venue(%s, %s, %s)", getId(), getCountry(), getName());
    }

    /**
     * Turn a JSON object into a venue.
     *
     * @param json The JSON object with correct keys.
     * @return The Venue representation of the object.
     */
    public static Venue fromJsonObject(JsonObject json) {
        return new Venue(json.getInteger("VenueID"),
                Country.fromJsonObject(json),
                json.getString("VenueName"),
                json.getString("VenueAddress"),
                json.getString("VenuePostcode"),
                json.getString("VenueAreaCode"),
                json.getString("VenuePhone"),
                new Coordinates(
                        json.getDouble("VenueLocationLatitude"),
                        json.getDouble("VenueLocationLongitude")));
    }
}
