import java.util.Random;

public class IPGenerator {

    private static final Random RANDOM = new Random();

    // Converts "a.b.c.d" to a 32-bit number (stored in a long to avoid sign issues)
    static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (String part : parts) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }

    // Converts a 32-bit number back to "a.b.c.d"
    static String longToIp(long value) {
        return ((value >> 24) & 0xFF) + "." +
               ((value >> 16) & 0xFF) + "." +
               ((value >> 8) & 0xFF) + "." +
               (value & 0xFF);
    }

    // Generates a random usable host IP inside the network (excludes network and broadcast addresses)
    static String generateIp(String networkAddress, int prefix) {
        int hostBits = 32 - prefix;
        long mask = (0xFFFFFFFFL << hostBits) & 0xFFFFFFFFL;
        long network = ipToLong(networkAddress) & mask;
        long totalHosts = 1L << hostBits;

        // offset between 1 and totalHosts - 2
        long offset = 1 + (long) (RANDOM.nextDouble() * (totalHosts - 2));
        return longToIp(network + offset);
    }

    static String firstIp(String networkAddress, int prefix) {
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return longToIp(ipToLong(networkAddress) & mask);
    }

    static String lastIp(String networkAddress, int prefix) {
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return longToIp((ipToLong(networkAddress) & mask) | (~mask & 0xFFFFFFFFL));
    }

    public static void main(String[] args) {
        // R1: 192.168.0.0 - 192.168.255.255 /16
        // R2: 172.16.0.0  - 172.31.255.255  /12
        String[][] networks = {
            {"R1", "192.168.0.0", "16"},
            {"R2", "172.16.0.0", "12"}
        };

        for (String[] n : networks) {
            String name = n[0];
            String address = n[1];
            int prefix = Integer.parseInt(n[2]);

            System.out.println(name + ": " + firstIp(address, prefix) + " to "
                    + lastIp(address, prefix) + "/" + prefix);
            System.out.println("  Generated IP: " + generateIp(address, prefix));
        }
    }
}
