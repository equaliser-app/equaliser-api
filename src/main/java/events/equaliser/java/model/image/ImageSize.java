package events.equaliser.java.model.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import events.equaliser.java.Config;
import events.equaliser.java.util.Hex;
import io.vertx.core.json.JsonObject;

public class ImageSize {

    private final int width;
    private final int height;
    private final byte[] crc32;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getCrc32() {
        return crc32;
    }

    private ImageSize(int width, int height, byte[] crc32) {
        this.width = width;
        this.height = height;
        this.crc32 = crc32;
    }

    @JsonProperty("url")
    public String getUrl() {
        return String.format("%s/images/%s.jpg", Config.STATIC_CONTENT_URL, Hex.binToHex(getCrc32()));
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
                json.getBinary("ImageCrc32"));
    }
}
