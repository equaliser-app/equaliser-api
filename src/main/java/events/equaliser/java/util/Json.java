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


public class Json {
    public static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static <K, V> JsonObject toJsonObject(Map<K, V> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            object.put(entry.getKey().toString(), entry.getValue());
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> fromJsonObject(JsonObject object) {
        Map<K, V> map = new HashMap<>();
        for (Map.Entry<String, Object> entry : object) {
            map.put((K)entry.getKey(), (V)entry.getValue());
        }
        return map;
    }

    public static <T> JsonArray toJsonArray(List<T> list) {
        JsonArray array = new JsonArray();
        for (T t : list) {
            array.add(t);
        }
        return array;
    }
}
