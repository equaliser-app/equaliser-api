package events.equaliser.java.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class Request {

    public static String getFormAttribute(HttpServerRequest request, String field) {
        return request.getFormAttribute(field);
    }

    public static String getParam(HttpServerRequest request, String field) {
        return request.getParam(field);
    }

    public static String validateField(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(String.format("'%s' param missing", name));
        }
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(String.format("'%s' param empty", name));
        }
        return value;
    }

    public static Map<String, String> parseData(HttpServerRequest request, List<String> names,
                                                BiFunction<HttpServerRequest, String, String> retriever) {
        Map<String, String> fields = new HashMap<>();
        for (String name : names) {
            String value = retriever.apply(request, name);  // POST (getFormAttribute()) or GET (getParam())
            fields.put(name, validateField(name, value));
        }
        return fields;
    }

    public static void missingParam(String name, Handler<AsyncResult<JsonNode>> handler) {
        handler.handle(Future.failedFuture(String.format("'%s' param missing", name)));
    }

    public static ObjectNode errorResponse(String message) {
        ObjectNode container = Json.FACTORY.objectNode();
        container.put("success", false);
        container.put("message", message);
        return container;
    }

    public static void writeSuccessResponse(HttpServerResponse response, JsonNode data) {
        ObjectNode container = Json.FACTORY.objectNode();
        container.put("success", true);
        container.set("result", data);
        writeResponse(response, container, 200);
    }

    public static void writeErrorResponse(HttpServerResponse response, String message) {
        writeResponse(response, errorResponse(message), 400);
    }

    public static void writeResponse(HttpServerResponse response, JsonNode node, int statusCode) {
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.setStatusCode(statusCode);
        String text;
        try {
            text = Json.MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // should be impossible, but we're covered
            text = "Server error";
        }
        response.end(text);
    }
}
