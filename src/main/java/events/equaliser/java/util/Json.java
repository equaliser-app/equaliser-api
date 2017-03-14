package events.equaliser.java.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;


public class Json {
    public static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
}
