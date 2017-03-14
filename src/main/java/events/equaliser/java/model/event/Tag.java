package events.equaliser.java.model.event;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a generic tag attached to something.
 */
public class Tag {

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

    public static void retrieveShowcase(SQLConnection connection,
                                        Handler<AsyncResult<Map<Integer, List<Tag>>>> handler) {
        connection.query(
                "SELECT Series.SeriesID, Tags.TagID, Tags.Name AS TagName " +
                "FROM Series " +
                    "INNER JOIN SeriesTags " +
                        "ON SeriesTags.SeriesID = Series.SeriesID " +
                    "INNER JOIN Tags " +
                        "ON Tags.TagID = SeriesTags.TagID " +
                "WHERE Series.IsShowcase = true;", res -> {
                    if (res.failed()) {
                        handler.handle(Future.failedFuture(res.cause()));
                        return;
                    }

                    ResultSet set = res.result();
                    Map<Integer, List<Tag>> map = new HashMap<>();
                    for (JsonObject row : set.getRows()) {
                        int seriesId = row.getInteger("SeriesID");
                        if (!map.containsKey(seriesId)) {
                            map.put(seriesId, new ArrayList<>());
                        }
                        map.get(seriesId).add(Tag.fromJsonObject(row));
                    }
                    handler.handle(Future.succeededFuture(map));
                });
    }
}
