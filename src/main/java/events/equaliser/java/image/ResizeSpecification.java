package events.equaliser.java.image;

import org.imgscalr.Scalr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

public class ResizeSpecification {

    public static final List<ResizeSpecification> PROFILE_PHOTO = Arrays.asList(
            new ResizeSpecification(500),
            new ResizeSpecification(200));

    public static final List<ResizeSpecification> SERIES_PHOTO = Arrays.asList(
            new ResizeSpecification(2048, 768),
            new ResizeSpecification(1024, 512));

    private final int width;
    private final int height;

    public ResizeSpecification(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public ResizeSpecification(int dimension) {
        this(dimension, dimension);
    }

    public ImageFile resize(ImageFile image) throws IOException, NoSuchAlgorithmException {
        BufferedImage buffer = Scalr.resize(image.getImage(), Scalr.Method.QUALITY, Scalr.Mode.FIT_EXACT,
                width, height);
        return new ImageFile(buffer);
    }
}
