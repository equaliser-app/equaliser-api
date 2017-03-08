package events.equaliser.java.model.geography;

/**
 * Represents a country, e.g. the United Kingdom.
 */
public class Country {

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

    public String getName() {
        return name;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public String getCallingCode() {
        return callingCode;
    }

    /**
     * Initialise a new country object.
     *
     * @param name The name of the country, e.g. "United Kingdom".
     * @param abbreviation The country's abbreviation, e.g. "UK".
     * @param callingCode The country's calling code, e.g. "44".
     */
    public Country(String name, String abbreviation, String callingCode) {
        this.name = name;
        this.abbreviation = abbreviation;
        this.callingCode = callingCode;
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
