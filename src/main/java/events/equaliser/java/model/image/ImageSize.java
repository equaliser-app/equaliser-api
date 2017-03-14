package events.equaliser.java.model.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.image.ImageFile;
import events.equaliser.java.model.event.Tag;
import events.equaliser.java.util.Hex;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.util.*;

public class ImageSize {

    private final int width;
    private final int height;
    private final byte[] sha256;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getSha256() {
        return sha256;
    }

    private ImageSize(int width, int height, byte[] sha256) {
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
    }

    private ImageSize(ImageFile file) {
        this(file.getWidth(), file.getHeight(), file.getSha256());  // TODO this is terrible - sort out these classes
    }

    @JsonProperty("url")
    public String getUrl() {
        String base = Vertx.currentContext().config().getString("static");
        return String.format("%s/images/%s.jpg", base, Hex.binToHex(getSha256()));
    }

    public static void insertBatch(List<ImageFile> sizes,
                                   int imageId,
                                   SQLConnection connection,
                                   Handler<AsyncResult<List<ImageSize>>> handler) {
        List<JsonArray> batch = new ArrayList<>();
        for (ImageFile file : sizes) {
            batch.add(new JsonArray()
                    .add(imageId)
                    .add(file.getWidth())
                    .add(file.getHeight())
                    .add(file.getSha256()));
        }
        connection.batchWithParams(
                "INSERT INTO ImageSizes (ImageID, Width, Height, Sha256) " +
                "VALUES (?, ?, ?, ?);",
                batch, insertRes -> {
                    if (insertRes.succeeded()) {
                        List<ImageSize> inserted = new ArrayList<>();
                        for (ImageFile file : sizes) {
                            inserted.add(new ImageSize(file));
                        }
                        handler.handle(Future.succeededFuture(inserted));
                    }
                    else {
                        handler.handle(Future.failedFuture(insertRes.cause()));
                    }
                });
    }

    /**
     * Turn a JSON object into an image size.
     *
     * @param json The JSON object with correct keys.
     * @return The ImageSize representation of the object.
     */
    static ImageSize fromJsonObject(JsonObject json) {
        return new ImageSize(
                json.getInteger("ImageWidth"),
                json.getInteger("ImageHeight"),
                json.getBinary("ImageSha256"));
    }

    public static void retrieveSeriesShowcase(SQLConnection connection,
                                              Handler<AsyncResult<Map<Integer, List<Image>>>> handler) {
        connection.query(
                "SELECT " +
                    "Series.SeriesID, " +
                    "SeriesImages.ImageID, " +
                    "ImageSizes.Width AS ImageWidth, " +
                    "ImageSizes.Height AS ImageHeight, " +
                    "ImageSizes.Sha256 AS ImageSha256 " +
                "FROM Series " +
                    "INNER JOIN SeriesImages " +
                        "ON SeriesImages.SeriesID = Series.SeriesID " +
                    "INNER JOIN ImageSizes " +
                        "ON ImageSizes.ImageID = SeriesImages.ImageID " +
                "WHERE Series.IsShowcase = true;", res -> processSeriesResult(res, handler));
    }

    public static void retrieveSeriesTag(Tag tag,
                                         SQLConnection connection,
                                         Handler<AsyncResult<Map<Integer, List<Image>>>> handler) {
        JsonArray params = new JsonArray().add(tag.getId());
        connection.queryWithParams(
                "SELECT " +
                    "Series.SeriesID, " +
                    "SeriesImages.ImageID, " +
                    "ImageSizes.Width AS ImageWidth, " +
                    "ImageSizes.Height AS ImageHeight, " +
                    "ImageSizes.Sha256 AS ImageSha256 " +
                "FROM Tags " +
                    "INNER JOIN SeriesTags " +
                        "ON SeriesTags.TagID = Tags.TagID " +
                    "INNER JOIN Series " +
                        "ON Series.SeriesID = SeriesTags.SeriesID " +
                    "INNER JOIN SeriesImages " +
                        "ON SeriesImages.SeriesID = Series.SeriesID " +
                    "INNER JOIN ImageSizes " +
                        "ON ImageSizes.ImageID = SeriesImages.ImageID " +
                "WHERE Tags.TagID = ?;", params, res -> processSeriesResult(res, handler));
    }

    private static void processSeriesResult(AsyncResult<ResultSet> result,
                                            Handler<AsyncResult<Map<Integer, List<Image>>>> handler) {
        if (result.failed()) {
            handler.handle(Future.failedFuture(result.cause()));
            return;
        }

        ResultSet set = result.result();
        Map<Integer, Map<Integer, List<ImageSize>>> series = new HashMap<>();
        for (JsonObject row : set.getRows()) {
            int seriesId = row.getInteger("SeriesID");
            if (!series.containsKey(seriesId)) {
                series.put(seriesId, new HashMap<>());
            }
            Map<Integer, List<ImageSize>> images = series.get(seriesId);
            int imageId = row.getInteger("ImageID");
            if (!images.containsKey(imageId)) {
                images.put(imageId, new ArrayList<>());
            }
            images.get(imageId).add(ImageSize.fromJsonObject(row));
        }
        Map<Integer, List<Image>> images = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, List<ImageSize>>> seriesEntry : series.entrySet()) {
            int seriesId = seriesEntry.getKey();
            images.put(seriesId, new ArrayList<>());
            Map<Integer, List<ImageSize>> seriesEntryValue = seriesEntry.getValue();
            for (Map.Entry<Integer, List<ImageSize>> seriesEntryValueEntry : seriesEntryValue.entrySet()) {
                int imageId = seriesEntryValueEntry.getKey();
                List<ImageSize> sizes = seriesEntryValueEntry.getValue();
                Image image = new Image(imageId, sizes);
                images.get(seriesId).add(image);
            }
        }
        handler.handle(Future.succeededFuture(images));
    }
}
