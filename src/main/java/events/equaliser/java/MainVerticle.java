package events.equaliser.java;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import events.equaliser.java.model.event.Series;
import events.equaliser.java.model.geography.Country;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.TriConsumer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
import java.util.UUID;

public class MainVerticle extends AbstractVerticle {

    private AsyncSQLClient client;
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        JsonObject config = config().getJsonObject("database");
        client = MySQLClient.createShared(vertx, config);

        Router router = Router.router(vertx);
        router.get("/countries").handler(routingContext -> delegateHandler(routingContext, this::countries));
        router.post("/auth/ephemeral").handler(routingContext -> delegateHandler(routingContext, this::ephemeralPost));
        router.get("/series/:id").handler(routingContext -> delegateHandler(routingContext, this::seriesSingle));

        final int listenPort = config().getJsonObject("webserver").getInteger("port");
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router::accept).listen(listenPort, handler -> {
            if (!handler.succeeded()) {
                System.err.println("Failed to listen on port " + listenPort);
            }
        });

        super.start(startFuture);
    }

    /*private void getCountries(RoutingContext context) {
        *//*
        Send the headers
        Open DB connection
        Query db
        Close DB connection
        Process the result into objects
        Send the preamble (response succeeded/failed)
        Turn the result objects into JSON
        Send the JSON

        Generically: we want something that returns a serialisable result, or indicates failure - a Handler<AsyncResult<T>>
            We then wrap the result in the appropriate context with the appropriate headers.

        Separately, would be good to have a helper that sorts getting a connection for us - we only hear if we get one.
         *//*
        HttpServerResponse response = context.response()
                .putHeader("Content-Type", "application/json; charset=utf-8");
        client.getConnection(handler -> {
            if (handler.succeeded()) {
                SQLConnection connection = handler.result();
                *//*Country.retrieveAll(connection, result -> connection.close(closed -> {
                    if (result.succeeded()) {
                        List<Country> countries = result.result();
                        try {
                            String json = mapper.writerFor(Iterator.class).writeValueAsString(countries.iterator());
                            response.end(json);
                        } catch (JsonProcessingException e) {
                            response.setStatusCode(500).end("Failed to serialise countries");
                        }
                    } else {
                        response.setStatusCode(500).end("Query failed");
                    }
                }));*//*
            }
            else {
                response.setStatusCode(500).end("Failed to get a connection from the pool");
            }
        });
    }*/

    private void delegateHandler(RoutingContext context,
                                 TriConsumer<HttpServerRequest,
                                         SQLConnection,
                                         Handler<AsyncResult<JsonNode>>> consumer) {
        HttpServerResponse response = context.response();
        client.getConnection(connection -> {
            if (connection.succeeded()) {
                consumer.accept(context.request(), connection.result(), done -> {
                    if (done.succeeded()) {
                        writeSuccessResponse(response, done.result());
                    }
                    else {
                        writeErrorResponse(response, String.valueOf(done.cause()));
                    }
                });
            }
            else {
                writeErrorResponse(response,"Failed to get a database connection from the pool");
            }
        });
    }

    private ObjectNode errorResponse(String message) {
        final JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode container = factory.objectNode();
        container.put("success", false);
        container.put("message", message);
        return container;
    }

    private void writeSuccessResponse(HttpServerResponse response, JsonNode data) {
        final JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode container = factory.objectNode();
        container.put("success", true);
        container.set("result", data);
        writeResponse(response, container, 200);
    }

    private void writeErrorResponse(HttpServerResponse response, String message) {
        writeResponse(response, errorResponse(message), 500);
    }

    private void writeResponse(HttpServerResponse response, ObjectNode node, int statusCode) {
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.setStatusCode(statusCode);
        String text;
        try {
            text = mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // should be impossible, but we're covered
            text = "Server error";
        }
        response.end(text);
    }

    private void countries(HttpServerRequest request,
                           SQLConnection connection,
                           Handler<AsyncResult<JsonNode>> result) {
        Country.retrieveAll(connection, data -> connection.close(closed -> {
            if (data.succeeded()) {
                List<Country> countries = data.result();
                JsonNode node = mapper.convertValue(countries, JsonNode.class);
                // TODO use id => country dict instead of list
                result.handle(Future.succeededFuture(node));
            } else {
                result.handle(Future.failedFuture(data.cause()));
            }
        }));
    }

    private void seriesSingle(HttpServerRequest request,
                        SQLConnection connection,
                        Handler<AsyncResult<JsonNode>> result) {
        try {
            int id = Integer.parseInt(request.getParam("id"));
            Series.retrieveFromId(id, connection, data -> {
                if (data.succeeded()) {
                    Series series = (Series) data.result();  // TODO fix cast - caused by generics erasure issue
                    JsonNode node = mapper.convertValue(series, JsonNode.class);
                    result.handle(Future.succeededFuture(node));
                }
                else {
                    result.handle(Future.failedFuture(data.cause()));
                }
            });
        }
        catch (NumberFormatException e) {
            result.handle(Future.failedFuture("Invalid series id"));
        }
    }

    private void ephemeralPost(HttpServerRequest request,
                               SQLConnection connection,
                               Handler<AsyncResult<JsonNode>> result) {
        User.retrieveFromId(2, connection, data -> {
            if (data.succeeded()) {
                User user = data.result();
                final JsonNodeFactory factory = JsonNodeFactory.instance;
                ObjectNode node = factory.objectNode();
                node.put("session_token", "C8C2E98B83198235C84A48440A08162E31FBE73495232FB0F449A390D66A2342");
                node.set("user", mapper.convertValue(user, JsonNode.class));
                result.handle(Future.succeededFuture(node));
            }
            else {
                result.handle(Future.failedFuture(data.cause()));
            }
        });
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        client.close(handler -> {
            if (handler.succeeded()) {
                stopFuture.complete();
            }
            else {
                stopFuture.fail(handler.cause());
            }
        });
    }
}
