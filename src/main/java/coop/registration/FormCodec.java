package coop.registration;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class FormCodec {
    private FormCodec() {
    }

    public static Map<String, String> decode(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate field: " + key);
            }
        }
        return result;
    }

    public static byte[] encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String enc(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '*') {
                out.write(c);
            } else {
                out.write('%');
                out.write(hex(c >> 4));
                out.write(hex(c & 0xF));
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(int nibble) {
        return nibble < 10 ? '0' + nibble : 'A' + (nibble - 10);
    }
}
