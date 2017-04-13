package events.equaliser.java.image;

import org.imgscalr.Scalr;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Defines how an image should be resized.
 */
public class ResizeSpecification {

    /**
     * The resize profile for a user profile picture.
     */
    public static final List<ResizeSpecification> PROFILE_PHOTO = Arrays.asList(
            new ResizeSpecification(500),
            new ResizeSpecification(200));

    /**
     * The resize profile for a series banner image.
     */
    public static final List<ResizeSpecification> SERIES_PHOTO = Arrays.asList(
            new ResizeSpecification(2048, 768),
            new ResizeSpecification(1024, 512));

    /**
     * The target width of the image.
     */
    private final int width;

    /**
     * The target height of the image.
     */
    private final int height;

    /**
     * Create a new resize specification.
     *
     * @param width The target width of the image.
     * @param height The target height of the image.
     */
    private ResizeSpecification(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Create a new square image resize specification.
     *
     * @param dimension The target width and height of the image.
     */
    private ResizeSpecification(int dimension) {
        this(dimension, dimension);
    }

    /**
     * Resize an image according to this specification.
     *
     * @param image The image to resize.
     * @return The resized image.
     */
    private BufferedImage resize(BufferedImage image) {
        return Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.FIT_EXACT, width, height);
    }

    /**
     * Resize an image to a variety of sizes.
     *
     * @param image The image to resize.
     * @param sizes The specifications to apply to the image.
     * @return A list of results corresponding to specifications passed in `sizes`.
     */
    public static List<BufferedImage> resize(BufferedImage image, List<ResizeSpecification> sizes) {
        return sizes.stream().map(spec -> spec.resize(image)).collect(Collectors.toList());
    }
}
