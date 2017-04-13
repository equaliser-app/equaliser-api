package events.equaliser.java.util;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Utilities related to the network and IP.
 */
public class Network {

    /**
     * Get the IPv6 representation of an address. This will not mutate IPv6 addresses;
     * IPv4 addresses will be turned into their IPv6-mapped equivalent.
     *
     * @param address The address to normalise.
     * @return The normalised address.
     */
    public static byte[] v6Normalise(InetAddress address) {
        if (address instanceof Inet6Address) {
            return address.getAddress();
        }

        byte[] v4 = address.getAddress();
        byte[] v6 = new byte[16];
        v6[10] = (byte)0xff;
        v6[11] = (byte)0xff;
        v6[12] = v4[0];
        v6[13] = v4[1];
        v6[14] = v4[2];
        v6[15] = v4[3];
        return v6;
    }
}
