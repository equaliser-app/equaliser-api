package events.equaliser.java.model.geography;


public class Coordinates {

    private final double latitude;
    private final double longitude;

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Coordinates(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String toString() {
        return String.format("%f,%f", getLatitude(), getLongitude());
    }
}
