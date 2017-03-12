package events.equaliser.java.util;

/**
 * Utility methods for converting between hex and binary data.
 */
public class Hex {

    /**
     * Turn a piece of binary into a lowercase hex string.
     *
     * @param bin The binary to convert.
     * @return The string as hex. Length with be `bin.length * 2`.
     */
    public static String binToHex(byte[] bin) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bin) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /**
     * Turn a hexadecimal string into its underlying binary representation.
     *
     * @param hex The hex string to parse.
     * @return The binary representation of the hex.
     */
    public static byte[] hexToBin(String hex) {
        int len = hex.length();

        if (len % 2 == 1) {
            throw new IllegalArgumentException("Hex strings must contain an even number of hexadecimal digits");
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
