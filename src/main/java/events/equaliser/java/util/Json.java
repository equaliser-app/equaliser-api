package events.equaliser.java.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Methods related to the transformation of data to and from JSON entities.
 */
public class Json {
    public static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Turn a map into a JSON object.
     * @param map The map to transform.
     * @param <K> The type of keys.
     * @param <V> The type of values.
     * @return The JsonObject.
     */
    public static <K, V> JsonObject toJsonObject(Map<K, V> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            object.put(entry.getKey().toString(), entry.getValue());
        }
        return object;
    }

    /**
     * Create a map from a JSON object.
     * @param object The object to parse.
     * @param <K> The type of keys; will always be a string.
     * @param <V> The type of values.
     * @return The parsed map.
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> fromJsonObject(JsonObject object) {
        Map<K, V> map = new HashMap<>();
        for (Map.Entry<String, Object> entry : object) {
            map.put((K)entry.getKey(), (V)entry.getValue());
        }
        return map;
    }

    /**
     * Turn a list into a JSON array.
     *
     * @param list The list to transform.
     * @param <T> The type of elements in the list.
     * @return The transformed list.
     */
    public static <T> JsonArray toJsonArray(List<T> list) {
        JsonArray array = new JsonArray();
        for (T t : list) {
            array.add(t);
        }
        return array;
    }
}
