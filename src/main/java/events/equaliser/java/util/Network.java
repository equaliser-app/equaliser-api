package events.equaliser.java.util;

import java.net.Inet6Address;
import java.net.InetAddress;


public class Network {

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
