package events.equaliser.java.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public class Filesystem {

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
