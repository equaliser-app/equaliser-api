package events.equaliser.java.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.model.image.Image;
import events.equaliser.java.model.image.ImageSize;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A series without fixtures.
 */
public class BareSeries {

    private final int id;

    private final String name;

    private final String description;

    private final List<Tag> tags;

    private final List<Image> images;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @JsonIgnore
    public List<Tag> getTags() {
        return tags;
    }

    @JsonProperty("tags")
    private List<String> getTagStrings() {
        return getTags().stream().map(Tag::getName).collect(Collectors.toList());
    }

    public List<Image> getImages() {
        return images;
    }

    public BareSeries(int id, String name, String description, List<Tag> tags, List<Image> images) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.tags = tags;
        this.images = images;
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<? extends BareSeries>> handler) {
        // retrieve tags
        Tag.retrieveFromSeries(id, connection, seriesTags -> {
            if (seriesTags.succeeded()) {
                List<Tag> tags = seriesTags.result();

                // retrieve images
                Image.retrieveFromSeries(id, connection, seriesImages -> {
                    if (seriesImages.succeeded()) {
                        List<Image> images = seriesImages.result();
                        JsonArray params = new JsonArray().add(id);
                        connection.queryWithParams(
                                "SELECT Name, Description " +
                                "FROM Series " +
                                "WHERE SeriesID = ?;",
                                params, seriesRes -> {
                                    if (seriesRes.succeeded()) {
                                        ResultSet rows = seriesRes.result();
                                        JsonObject row = rows.getRows().get(0);
                                        String name = row.getString("Name");
                                        String description = row.getString("Description");
                                        handler.handle(Future.succeededFuture(
                                                new BareSeries(id, name, description, tags, images)));
                                    }
                                    else {
                                        handler.handle(Future.failedFuture(seriesRes.cause()));
                                    }
                                });
                    }
                    else {
                        handler.handle(Future.failedFuture(seriesImages.cause()));
                    }
                });
            }
            else {
                handler.handle(Future.failedFuture(seriesTags.cause()));
            }
        });
    }

    public static void retrieveShowcase(SQLConnection connection,
                                        Handler<AsyncResult<List<BareSeries>>> handler) {
        Tag.retrieveShowcase(connection, tagsResult -> {
            if (tagsResult.failed()) {
                handler.handle(Future.failedFuture(tagsResult.cause()));
                return;
            }

            Map<Integer, List<Tag>> tagsMap = tagsResult.result();
            ImageSize.retrieveShowcase(connection, imagesResult -> {
                if (imagesResult.failed()) {
                    handler.handle(Future.failedFuture(imagesResult.cause()));
                    return;
                }

                Map<Integer, List<Image>> imagesMap = imagesResult.result();
                connection.query(
                        "SELECT SeriesID, Name, Description " +
                        "FROM Series " +
                        "WHERE IsShowcase = true;",
                        seriesResult -> {
                            if (seriesResult.failed()) {
                                handler.handle(Future.failedFuture(seriesResult.cause()));
                                return;
                            }

                            ResultSet seriesResults = seriesResult.result();
                            List<BareSeries> series = new ArrayList<>();
                            for (JsonObject row : seriesResults.getRows()) {
                                int seriesId = row.getInteger("SeriesID");
                                String name = row.getString("Name");
                                String description = row.getString("Description");
                                series.add(new BareSeries(seriesId, name, description,
                                        tagsMap.get(seriesId), imagesMap.get(seriesId)));
                            }
                            handler.handle(Future.succeededFuture(series));
                        });

            });
        });
    }
}
