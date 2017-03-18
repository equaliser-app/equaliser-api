package events.equaliser.java.model.auth;

import io.vertx.core.json.JsonObject;


public class SecurityEventType {

    public static final int USER_LOGIN = 1;
    public static final int EPHEMERAL_TOKEN_REQUEST = 2;

    private final int id;
    private final String name;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    private static String getName(int constant) {
        switch (constant) {
            case USER_LOGIN:
                return "user.login";
            case EPHEMERAL_TOKEN_REQUEST:
                return "ephemeral_token.request";
            default:
                throw new IllegalArgumentException("Invalid constant: " + constant);
        }
    }

    public SecurityEventType(int id) {
        this(id, getName(id));
    }

    private SecurityEventType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * Turn a JSON object into a security event type.
     *
     * @param json The JSON object with correct keys.
     * @return The SecurityEventType representation of the object.
     */
    static SecurityEventType fromJsonObject(JsonObject json) {
        return new SecurityEventType(
                json.getInteger("SecurityEventTypeID"),
                json.getString("SecurityEventTypeName"));
    }

    public String toString() {
        return getName();
    }
}
