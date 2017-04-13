package events.equaliser.java.util;

import io.vertx.core.Vertx;
import io.vertx.ext.auth.PRNG;

/**
 * Utilities related to random number generation.
 * N.B. blocking!
 */
public class Random {

    private static final PRNG random = new PRNG(Vertx.currentContext().owner());

    /**
     * Retrieve a number of random bytes.
     *
     * @param length The number of bytes to retrieve.
     * @return The random bytes, of length `length`.
     */
    public static byte[] getBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Retrieve a string of `length` random numbers.
     *
     * @param length The number of random digits to generate.
     * @return The generated digits, precisely `length` long.
     */
    public static String getNumericString(int length) {
        byte[] bytes = getBytes(length);
        StringBuilder string = new StringBuilder();
        for (byte b : bytes) {
            // FIXME % means numbers 0-5 are more likely than 6-9
            string.append(Character.forDigit(Math.abs(b) % 10, 10));
        }
        return string.toString();
    }
}
