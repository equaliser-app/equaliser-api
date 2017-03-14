package events.equaliser.java.model.event;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a generic tag attached to something.
 */
public class Tag {

    private static final Logger logger = LoggerFactory.getLogger(Tag.class);

    private final int id;

    private final String name;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    private Tag(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * Turn a JSON object into a tag.
     *
     * @param json The JSON object with correct keys.
     * @return The Tag representation of the object.
     */
    private static Tag fromJsonObject(JsonObject json) {
        return new Tag(
                json.getInteger("TagID"),
                json.getString("TagName"));
    }

    @Override
    public String toString() {
        return String.format("Tag(%d, %s)", getId(), getName());
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<Tag>> result) {
        JsonArray params = new JsonArray().add(id);
        connection.queryWithParams(
                "SELECT " +
                    "TagID, " +
                    "Name AS TagName " +
                "FROM Tags " +
                "WHERE TagID = ?;",
                params, tagResult -> {
                    if (tagResult.failed()) {
                        result.handle(Future.failedFuture(tagResult.cause()));
                        return;
                    }

                    ResultSet resultSet = tagResult.result();
                    if (resultSet.getNumRows() == 0) {
                        result.handle(Future.failedFuture("No tag found with id " + id));
                        return;
                    }

                    JsonObject row = resultSet.getRows().get(0);
                    Tag tag = Tag.fromJsonObject(row);
                    result.handle(Future.succeededFuture(tag));
                });
    }

    public static void retrieveFromName(String name,
                                        SQLConnection connection,
                                        Handler<AsyncResult<Tag>> result) {
        JsonArray params = new JsonArray().add(name);
        connection.queryWithParams(
                "SELECT " +
                    "TagID, " +
                    "Name AS TagName " +
                "FROM Tags " +
                "WHERE Name = ?;",
                params, tagResult -> {
                    if (tagResult.failed()) {
                        logger.error("Failed to find tag with name '{}'", name, tagResult.cause());
                        result.handle(Future.failedFuture(tagResult.cause()));
                        return;
                    }

                    ResultSet resultSet = tagResult.result();
                    if (resultSet.getNumRows() == 0) {
                        result.handle(Future.failedFuture("No tag found with name " + name));
                        return;
                    }

                    JsonObject row = resultSet.getRows().get(0);
                    Tag tag = Tag.fromJsonObject(row);
                    result.handle(Future.succeededFuture(tag));
                });
    }

    public static void retrieveFromSeries(int seriesId,
                                          SQLConnection connection,
                                          Handler<AsyncResult<List<Tag>>> result) {
        JsonArray params = new JsonArray().add(seriesId);
        connection.queryWithParams(
                "SELECT " +
                    "Tags.TagID, " +
                    "Tags.Name AS TagName " +
                "FROM SeriesTags " +
                    "INNER JOIN Tags " +
                        "ON Tags.TagID = SeriesTags.TagID " +
                "WHERE SeriesTags.SeriesID = ?;",
                params, tagsResult -> {
                    if (tagsResult.succeeded()) {
                        ResultSet resultSet = tagsResult.result();
                        if (resultSet.getNumRows() == 0) {
                            // could just return an empty list, but all series should have tags
                            result.handle(Future.failedFuture("No tags found for series id " + seriesId));
                        }
                        else {
                            List<Tag> tags = resultSet
                                    .getRows()
                                    .stream()
                                    .map(Tag::fromJsonObject)
                                    .collect(Collectors.toList());
                            result.handle(Future.succeededFuture(tags));
                        }
                    }
                    else {
                        result.handle(Future.failedFuture(tagsResult.cause()));
                    }
                });
    }

    public static void retrieveSeriesShowcase(SQLConnection connection,
                                              Handler<AsyncResult<Map<Integer, List<Tag>>>> handler) {
        connection.query(
                "SELECT Series.SeriesID, Tags.TagID, Tags.Name AS TagName " +
                "FROM Series " +
                    "INNER JOIN SeriesTags " +
                        "ON SeriesTags.SeriesID = Series.SeriesID " +
                    "INNER JOIN Tags " +
                        "ON Tags.TagID = SeriesTags.TagID " +
                "WHERE Series.IsShowcase = true;", res -> processSeriesResult(res, handler));
    }

    public static void retrieveSeriesTag(Tag tag,
                                         SQLConnection connection,
                                         Handler<AsyncResult<Map<Integer, List<Tag>>>> handler) {
        JsonArray params = new JsonArray().add(tag.getId());
        connection.queryWithParams(
                "SELECT " +
                    "Series.SeriesID, " +
                    "T2.TagID, " +
                    "T2.Name AS TagName " +
                "FROM Tags AS T1 " +
                    "INNER JOIN SeriesTags AS ST1 " +
                        "ON ST1.TagID = T1.TagID " +
                    "INNER JOIN Series " +
                        "ON Series.SeriesID = ST1.SeriesID " +
                    "INNER JOIN SeriesTags AS ST2 " +
                        "ON ST2.SeriesID = Series.SeriesID " +
                    "INNER JOIN Tags AS T2 " +
                        "ON T2.TagID = ST2.TagID " +
                "WHERE T1.TagID = ?;", params, res -> processSeriesResult(res, handler));
    }

    private static void processSeriesResult(AsyncResult<ResultSet> result,
                                            Handler<AsyncResult<Map<Integer, List<Tag>>>> handler) {
        if (result.failed()) {
            handler.handle(Future.failedFuture(result.cause()));
            return;
        }

        ResultSet set = result.result();
        Map<Integer, List<Tag>> map = new HashMap<>();
        for (JsonObject row : set.getRows()) {
            int seriesId = row.getInteger("SeriesID");
            if (!map.containsKey(seriesId)) {
                map.put(seriesId, new ArrayList<>());
            }
            map.get(seriesId).add(Tag.fromJsonObject(row));
        }
        handler.handle(Future.succeededFuture(map));
    }
}
