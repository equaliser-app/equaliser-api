package events.equaliser.java.image;

import org.imgscalr.Scalr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    public BufferedImage resize(BufferedImage image) {
        return Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.FIT_EXACT, width, height);
    }

    public static List<BufferedImage> resize(BufferedImage image, List<ResizeSpecification> sizes) {
        return sizes.stream().map(spec -> spec.resize(image)).collect(Collectors.toList());
    }
}
