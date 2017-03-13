package events.equaliser.java.model.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.Config;
import events.equaliser.java.image.ImageFile;
import events.equaliser.java.util.Hex;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;

import java.util.ArrayList;
import java.util.List;

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
        return String.format("%s/images/%s.jpg", Config.STATIC_CONTENT_URL, Hex.binToHex(getSha256()));
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
}
