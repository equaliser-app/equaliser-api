package events.equaliser.java.model.auth;

import events.equaliser.java.auth.Session;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Time;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.RoutingContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;


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
                    .add(client.getAddress())
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
