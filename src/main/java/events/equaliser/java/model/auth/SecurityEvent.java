package events.equaliser.java.model.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Network;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single security event, e.g. a user login, or ephemeral token generation.
 */
public class SecurityEvent {

    private static final Logger logger = LoggerFactory.getLogger(SecurityEvent.class);

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

    @JsonIgnore
    public InetAddress getIp() {
        return ip;
    }

    @JsonProperty("ip")
    private String getIpString() {
        return getIp().getHostAddress();
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
    private static SecurityEvent fromJsonObject(JsonObject json) throws UnknownHostException {
        return new SecurityEvent(
                json.getInteger("SecurityEventID"),
                SecurityEventType.fromJsonObject(json),
                InetAddress.getByAddress(json.getBinary("SecurityEventIPAddress")),
                Time.parseOffsetDateTime(json.getString("SecurityEventTimestamp")));
    }

    public String toString() {
        return String.format("SecurityEvent(%s, %s, %s)", getType().getName(), getIp(), getTimestamp());
    }

    public static void create(RoutingContext context, SecurityEventType type,
                              SQLConnection connection,
                              Handler<AsyncResult<SecurityEvent>> handler) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            InetAddress client = InetAddress.getByName(context.request().remoteAddress().host());
            Session session = context.get("session");
            JsonArray params = new JsonArray()
                    .add(type.getId())
                    .add(session.getId())
                    .add(Network.v6Normalise(client))
                    .add(Time.toSql(now));
            connection.updateWithParams(
                    "INSERT INTO SecurityEvents (SecurityEventTypeID, SessionID, IPAddress, Timestamp) " +
                    "VALUES (?, ?, FROM_BASE64(?), ?);", params, eventRes -> {
                        if (eventRes.failed()) {
                            handler.handle(Future.failedFuture(eventRes.cause()));
                            return;
                        }

                        int securityEventId = eventRes.result().getKeys().getInteger(0);
                        SecurityEvent event = new SecurityEvent(securityEventId, type, client, now);
                        handler.handle(Future.succeededFuture(event));
                    });
        } catch (UnknownHostException e) {
            handler.handle(Future.failedFuture(e));
        }
    }

    public static void retrieveByUser(User user, int limit,
                                      SQLConnection connection,
                                      Handler<AsyncResult<List<SecurityEvent>>> handler) {
        JsonArray params = new JsonArray()
                .add(user.getId())
                .add(limit);
        connection.queryWithParams(
                "SELECT " +
                    "SecurityEvents.SecurityEventID, " +
                    "SecurityEvents.IPAddress AS SecurityEventIPAddress, " +
                    "SecurityEvents.Timestamp AS SecurityEventTimestamp, " +
                    "SecurityEventTypes.SecurityEventTypeID, " +
                    "SecurityEventTypes.Name AS SecurityEventTypeName " +
                "FROM Sessions " +
                    "INNER JOIN SecurityEvents " +
                        "ON SecurityEvents.SessionID = Sessions.SessionID " +
                    "INNER JOIN SecurityEventTypes " +
                        "ON SecurityEventTypes.SecurityEventTypeID = SecurityEvents.SecurityEventTypeID " +
                "WHERE Sessions.UserID = ? " +
                "ORDER BY SecurityEvents.Timestamp DESC " +
                "LIMIT ?;", params, eventsRes -> {
                    if (eventsRes.failed()) {
                        handler.handle(Future.failedFuture(eventsRes.cause()));
                        return;
                    }

                    List<SecurityEvent> events = new ArrayList<>();
                    try {
                        for (JsonObject row : eventsRes.result().getRows()) {
                            events.add(fromJsonObject(row));
                        }
                    } catch (UnknownHostException e) {
                        handler.handle(Future.failedFuture(e));
                    }
                    handler.handle(Future.succeededFuture(events));
                });
    }
}
