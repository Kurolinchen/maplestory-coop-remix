package coop.registration;

public record Response(int status, String body, String contentType, String setCookie) {
    public static Response html(String body, String setCookie) {
        return new Response(200, body, "text/html; charset=utf-8", setCookie);
    }

    public static Response plain(String body) {
        return new Response(200, body, "text/plain; charset=utf-8", "");
    }

    public static Response badRequest() {
        return new Response(400, "Bad request", "text/plain; charset=utf-8", "");
    }

    public static Response notFound() {
        return new Response(404, "Not found", "text/plain; charset=utf-8", "");
    }

    public static Response methodNotAllowed() {
        return new Response(405, "Method not allowed", "text/plain; charset=utf-8", "");
    }

    public static Response payloadTooLarge() {
        return new Response(413, "Payload too large", "text/plain; charset=utf-8", "");
    }

    public static Response rateLimited() {
        return new Response(429, "Too many requests", "text/plain; charset=utf-8", "");
    }

    public static Response denied() {
        return new Response(403, "Forbidden", "text/plain; charset=utf-8", "");
    }

    public static Response forbidden() {
        return new Response(403, "Forbidden", "text/plain; charset=utf-8", "");
    }

    public static Response sessionExpired() {
        return new Response(429, "Session expired or invalid. Reload the form and try again.",
                "text/plain; charset=utf-8", "");
    }

    public static Response unavailable() {
        return new Response(503, "Service unavailable", "text/plain; charset=utf-8", "");
    }
}
