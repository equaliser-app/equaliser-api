package events.equaliser.java.model.image;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Image {

    private final int id;

    private final List<ImageSize> sizes;

    @JsonIgnore
    public int getId() {
        return id;
    }

    public List<ImageSize> getSizes() {
        return sizes;
    }

    Image(int id, List<ImageSize> sizes) {
        this.id = id;
        this.sizes = sizes;
    }

    private static Image fromEntry(Map.Entry<Integer, List<ImageSize>> entry) {
        return new Image(entry.getKey(), entry.getValue());
    }

    public String toString() {
        return String.format("Image(%d, %d sizes)", getId(), getSizes().size());
    }

    public static void insert(List<ImageSize> sizes,
                              SQLConnection connection,
                              Handler<AsyncResult<Image>> handler) {
        connection.update(
                "INSERT INTO Images VALUES ()",
                imageRes -> {
                    if (imageRes.succeeded()) {
                        UpdateResult imageResUpdate = imageRes.result();
                        int imageId = imageResUpdate.getKeys().getInteger(0);
                        ImageSize.insertBatch(sizes, imageId, connection, sizesRes -> {
                            if (sizesRes.succeeded()) {
                                Image image = new Image(imageId, sizes);
                                handler.handle(Future.succeededFuture(image));
                            }
                            else {
                                handler.handle(Future.failedFuture(sizesRes.cause()));
                            }
                        });
                    }
                    else {
                        handler.handle(Future.failedFuture(imageRes.cause()));
                    }
                });
    }

    public static void retrieveFromId(int id, SQLConnection connection, Handler<AsyncResult<Image>> result) {
        JsonArray params = new JsonArray().add(id);
        connection.queryWithParams(
                "SELECT ImageID, Width AS ImageWidth, Height as ImageHeight, Sha256 as ImageSha256 " +
                "FROM ImageSizes " +
                "WHERE ImageID = ? " +
                "ORDER BY ImageWidth * ImageHeight ASC;", params, query -> {
            if (query.succeeded()) {
                ResultSet resultSet = query.result();
                if (resultSet.getNumRows() == 0) {
                    result.handle(Future.failedFuture("No image found with id " + id));
                }
                else {
                    List<ImageSize> sizes = query.result()
                            .getRows()
                            .stream()
                            .map(ImageSize::fromJsonObject)
                            .collect(Collectors.toList());
                    result.handle(Future.succeededFuture(new Image(id, sizes)));
                }
            }
            else {
                result.handle(Future.failedFuture(query.cause()));
            }
        });
    }

    public static void retrieveFromSeries(int seriesId,
                                          SQLConnection connection,
                                          Handler<AsyncResult<List<Image>>> result) {
        JsonArray params = new JsonArray().add(seriesId);
        connection.queryWithParams(
                "SELECT " +
                    "ImageSizes.ImageID, " +
                    "ImageSizes.Width AS ImageWidth, " +
                    "ImageSizes.Height AS ImageHeight, " +
                    "ImageSizes.Sha256 AS ImageSha256 " +
                "FROM SeriesImages " +
                    "INNER JOIN ImageSizes " +
                        "ON ImageSizes.ImageID = SeriesImages.ImageID " +
                "WHERE SeriesImages.SeriesID = ?;",
                params, imagesResult -> {
                    if (imagesResult.succeeded()) {
                        ResultSet resultSet = imagesResult.result();
                        Map<Integer, List<ImageSize>> imageSizes = new HashMap<>();
                        for (JsonObject row : resultSet.getRows()) {
                            int imageId = row.getInteger("ImageID");
                            if (!imageSizes.containsKey(imageId)) {
                                imageSizes.put(imageId, new ArrayList<>());
                            }
                            imageSizes.get(imageId).add(ImageSize.fromJsonObject(row));
                        }

                        List<Image> images = imageSizes.entrySet()
                                .stream()
                                .map(Image::fromEntry)
                                .collect(Collectors.toList());
                        result.handle(Future.succeededFuture(images));
                    }
                    else {
                        result.handle(Future.failedFuture(imagesResult.cause()));
                    }
                });
    }
}
