package coop.registration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class RegistrationServerMain {
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(15);
    private static final long GLOBAL_WINDOW_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final long SESSION_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final long ENTRY_TTL_NANOS = TimeUnit.HOURS.toNanos(2);

    private RegistrationServerMain() {
    }

    public static void main(String[] args) throws IOException {
        RegistrationConfig config = new RegistrationConfig(
                required("REG_PUBLIC_ORIGIN"),
                required("REG_JDBC_URL"),
                required("REG_DB_USER"),
                secret("REG_DB_PASSWORD_FILE"),
                secret("REG_INVITE_FILE"),
                Integer.parseInt(envOrDefault("REG_PORT", "8080")),
                Integer.parseInt(envOrDefault("REG_PER_IP_BURST", "2")),
                WINDOW_NANOS,
                Integer.parseInt(envOrDefault("REG_GLOBAL_HOURLY_CAP", "20")),
                SESSION_TTL_NANOS,
                envOrDefault("REG_RESOURCE_DIR", "/opt/registration/public"));

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(5));
        HikariDataSource dataSource = new HikariDataSource(hikari);

        AccountRegistrationRepository repository = new AccountRegistrationRepository(dataSource);
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                System::nanoTime, config.perIpBurst(), config.windowNanos(), config.globalHourlyCap(),
                GLOBAL_WINDOW_NANOS, ENTRY_TTL_NANOS);
        RegistrationHandler handler = new RegistrationHandler(config, repository, limiter, Clock.systemUTC());
        Set<String> trustedProxyIps = trustedProxyIps();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 0);
        server.createContext("/health/ready", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/health/ready")) {
                respond(exchange, Response.notFound());
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, Response.methodNotAllowed());
            } else {
                respond(exchange, handler.health());
            }
        });
        server.createContext("/register", exchange -> {
            if (!exchange.getRequestURI().getPath().equals(RegistrationHandler.PAGE_PATH)) {
                respond(exchange, Response.notFound());
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, handler.get(header(exchange, "Origin"), header(exchange, "Host"),
                        clientKey(exchange, trustedProxyIps)));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                byte[] body = readBoundedBody(exchange);
                if (body == null) {
                    respond(exchange, Response.payloadTooLarge());
                    return;
                }
                respond(exchange, handler.post(header(exchange, "Origin"), header(exchange, "Host"),
                        header(exchange, "Content-Type"), header(exchange, "Cookie"),
                        clientKey(exchange, trustedProxyIps), body));
                return;
            }
            respond(exchange, Response.methodNotAllowed());
        });
        server.createContext("/", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, Response.methodNotAllowed());
            } else if (!hostAllowed(header(exchange, "Host"), config.publicOrigin())) {
                respond(exchange, Response.denied());
            } else {
                staticResponse(exchange, Path.of(config.resourceDir()));
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private static void staticResponse(HttpExchange exchange, Path root) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (!path.equals("/index.html") && !path.equals("/assets/register.css")) {
            respond(exchange, Response.notFound());
            return;
        }
        Path resolved = root.resolve(path.replaceFirst("^/", "")).normalize();
        if (!resolved.startsWith(root.normalize()) || !Files.isRegularFile(resolved)) {
            respond(exchange, Response.notFound());
            return;
        }
        byte[] bytes = Files.readAllBytes(resolved);
        String type = resolved.toString().endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        exchange.getResponseHeaders().add("Content-Type", type);
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        if (response.setCookie() != null && !response.setCookie().isEmpty()) {
            exchange.getResponseHeaders().add("Set-Cookie", response.setCookie());
        }
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static boolean hostAllowed(String host, String publicOrigin) {
        return host != null && host.equalsIgnoreCase(java.net.URI.create(publicOrigin).getHost());
    }

    private static byte[] readBoundedBody(HttpExchange exchange) throws IOException {
        return readBoundedBody(exchange.getRequestBody(), header(exchange, "Content-Length"));
    }

    static byte[] readBoundedBody(InputStream input, String contentLength) throws IOException {
        int maxBytes = 16 * 1024;
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > maxBytes) {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] body = input.readNBytes(maxBytes + 1);
        return body.length > maxBytes ? null : body;
    }

    private static String clientKey(HttpExchange exchange, Set<String> trustedProxyIps) {
        String peerIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        return TrustedProxyClientIp.resolve(peerIp, header(exchange, "X-Forwarded-For"), trustedProxyIps);
    }

    private static Set<String> trustedProxyIps() {
        return Arrays.stream(envOrDefault("REG_TRUSTED_PROXY_IPS", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private static String secret(String fileKey) {
        String path = required(fileKey);
        try {
            String value = Files.readString(Path.of(path)).trim();
            if (value.isEmpty()) {
                throw new IllegalStateException("Secret file is empty: " + path);
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read secret file: " + path, e);
        }
    }
}
