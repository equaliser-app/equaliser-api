package events.equaliser.java.model.auth;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;


public class SecurityEvent {

    private final int id;
    private final SecurityEventType type;
    private final InetAddress ip;
    private final OffsetDateTime timestamp;

    public int getId() {
        return id;
    }

    public SecurityEventType getType() {
        return type;
    }

    public InetAddress getIp() {
        return ip;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    private SecurityEvent(int id, SecurityEventType type, InetAddress ip, OffsetDateTime timestamp) {
        this.id = id;
        this.type = type;
        this.ip = ip;
        this.timestamp = timestamp;
    }

    /**
     * Turn a JSON object into a security event type.
     *
     * @param json The JSON object with correct keys.
     * @return The SecurityEventType representation of the object.
     */
    static SecurityEvent fromJsonObject(JsonObject json) throws UnknownHostException {
        return new SecurityEvent(
                json.getInteger("SecurityEventID"),
                SecurityEventType.fromJsonObject(json),
                InetAddress.getByAddress(json.getBinary("SecurityEventIPAddress")),
                OffsetDateTime.ofInstant(json.getInstant("SecurityEventTimestamp"), ZoneOffset.UTC));
    }

    public String toString() {
        return String.format("SecurityEvent(%s, %s, %s)", getType().getName(), getIp(), getTimestamp());
    }
}
