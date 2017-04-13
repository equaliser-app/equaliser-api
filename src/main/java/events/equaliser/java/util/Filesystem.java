package events.equaliser.java.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utilities related to filesystem interaction.
 */
public class Filesystem {

    /**
     * Calculate the SHA-256 checksum of a file.
     * @param file The file path.
     * @return The raw hash bytes. Always 32 long.
     * @throws NoSuchAlgorithmException If the JRE does not have SHA-256 installed.
     * @throws IOException If the file cannot be read.
     */
    public static byte[] sha256(File file) throws NoSuchAlgorithmException, IOException {
        byte[] buffer = new byte[8192];
        int count;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        BufferedInputStream stream = new BufferedInputStream(new FileInputStream(file));
        while ((count = stream.read(buffer)) > 0) {
            digest.update(buffer, 0, count);
        }
        return digest.digest();
    }
}
