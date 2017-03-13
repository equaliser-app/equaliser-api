package events.equaliser.java.image;

import events.equaliser.java.util.Filesystem;
import io.vertx.ext.web.FileUpload;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;


public class ImageFile {

    private final File file;
    private final BufferedImage image;
    private final byte[] sha256;

    public File getFile() {
        return file;
    }

    public BufferedImage getImage() {
        return image;
    }

    public byte[] getSha256() {
        return sha256;
    }

    public int getWidth() {
        return getImage().getWidth();
    }

    public int getHeight() {
        return getImage().getHeight();
    }

    public ImageFile(FileUpload upload) throws IOException, NoSuchAlgorithmException {
        this.file = new File(upload.uploadedFileName());
        this.image = ImageIO.read(file);
        this.sha256 = Filesystem.sha256(file);
    }

    ImageFile(BufferedImage image) throws IOException, NoSuchAlgorithmException {
        this.file = File.createTempFile("", ".jpg");
        this.image = image;
        this.sha256 = Filesystem.sha256(file);
    }

    public List<ImageFile> resize(List<ResizeSpecification> sizes) throws IOException, NoSuchAlgorithmException {
        List<ImageFile> resized = new ArrayList<>();
        for (ResizeSpecification spec : sizes) {
            resized.add(spec.resize(this));
        }
        return resized;
    }
}
