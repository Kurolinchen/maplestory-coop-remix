package coop.registration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

final class TrustedProxyClientIp {
    private TrustedProxyClientIp() {
    }

    static String resolve(String peerIp, String forwardedFor, Set<String> trustedProxyIps) {
        if (!trustedProxyIps.contains(peerIp) || forwardedFor == null || forwardedFor.contains(",")) {
            return peerIp;
        }
        String candidate = forwardedFor.trim();
        String normalized = isIpLiteral(candidate) ? normalize(candidate) : null;
        return normalized == null ? peerIp : normalized;
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return value.matches("[0-9A-Fa-f:.%]+") && normalize(value) != null;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
