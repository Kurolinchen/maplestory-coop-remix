package coop.registration;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import coop.registration.RegistrationValidator.Result;

public final class RegistrationHandler {
    public static final String PAGE_PATH = "/register";
    private static final String SESSION_COOKIE = "coop_reg_session";
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int DEFAULT_MAX_PENDING_SESSIONS = 256;
    private static final int MAX_PENDING_SESSIONS_PER_CLIENT = 4;

    private final RegistrationConfig config;
    private final AccountRegistrationRepository repository;
    private final RegistrationRateLimiter limiter;
    private final Clock clock;
    private final int maxPendingSessions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public RegistrationHandler(RegistrationConfig config,
                               AccountRegistrationRepository repository,
                               RegistrationRateLimiter limiter,
                               Clock clock) {
        this(config, repository, limiter, clock, DEFAULT_MAX_PENDING_SESSIONS);
    }

    RegistrationHandler(RegistrationConfig config,
                        AccountRegistrationRepository repository,
                        RegistrationRateLimiter limiter,
                        Clock clock,
                        int maxPendingSessions) {
        this.config = config;
        this.repository = repository;
        this.limiter = limiter;
        this.clock = clock;
        this.maxPendingSessions = maxPendingSessions;
        if (maxPendingSessions <= 0) {
            throw new IllegalArgumentException("maxPendingSessions must be positive");
        }
    }

    public Response get(String origin, String host, String clientKey) {
        if (!originAllowed(origin, host)) {
            return Response.denied();
        }
        return newForm(null, clientKey);
    }

    public Response post(String origin,
                         String host,
                         String contentType,
                         String cookieHeader,
                         String clientKey,
                         byte[] body) {
        if (!originAllowed(origin, host)) {
            return Response.denied();
        }
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            return Response.badRequest();
        }
        if (body == null || body.length > MAX_BODY_BYTES) {
            return Response.badRequest();
        }

        String sessionId = sessionIdFromCookie(cookieHeader);
        Session session = sessionId == null ? null : consumeSession(sessionId);
        if (session == null || session.expired(clock.instant().toEpochMilli(), config.sessionTtlNanos())) {
            return Response.sessionExpired();
        }

        Map<String, String> form;
        try {
            form = FormCodec.decode(body);
        } catch (IllegalArgumentException e) {
            return Response.badRequest();
        }
        String csrf = form.get("csrf");
        if (csrf == null || !RegistrationValidator.constantTimeEquals(csrf, session.csrfToken())) {
            return Response.denied();
        }

        if (!limiter.allow(clientKey)) {
            return Response.sessionExpired();
        }

        String invite = form.get("invite");
        if (!config.inviteMatches(invite)) {
            return newForm("Einladungscode ist ungültig.", clientKey);
        }

        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        String confirmation = form.getOrDefault("confirmation", "");
        Result result = RegistrationValidator.validate(username, password, confirmation);
        if (!result.accepted()) {
            return newForm(messageFor(result.status()), clientKey);
        }

        AccountCreationResult created = repository.create(result.username(), password);
        return switch (created) {
            case CREATED -> Response.html(Pages.successPage(), "");
            case DUPLICATE -> newForm("Der Account konnte nicht erstellt werden. Bitte anderen Namen wählen.", clientKey);
            case UNAVAILABLE -> Response.unavailable();
        };
    }

    public Response health() {
        return repository.isReachable() ? Response.plain("ready") : Response.unavailable();
    }

    private boolean originAllowed(String origin, String host) {
        return host != null
                && host.equalsIgnoreCase(java.net.URI.create(config.publicOrigin()).getHost())
                && (origin == null || origin.equalsIgnoreCase(config.publicOrigin()));
    }

    private String messageFor(RegistrationValidator.Status status) {
        return switch (status) {
            case USERNAME_INVALID -> "Benutzername: 4-13 Zeichen, nur A-Z, a-z, 0-9 und _.";
            case PASSWORD_INVALID -> "Passwort: 12-64 Zeichen, keine Steuerzeichen.";
            case PASSWORD_MISMATCH -> "Die beiden Passwörter stimmen nicht überein.";
            case OK -> "";
        };
    }

    private String newSessionId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sessionCookie(String sessionId) {
        return SESSION_COOKIE + "=" + sessionId + "; Path=" + PAGE_PATH
                + "; HttpOnly; Secure; SameSite=Strict; Max-Age=600";
    }

    private String sessionIdFromCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(SESSION_COOKIE)) {
                return pair[1];
            }
        }
        return null;
    }

    private synchronized Response newForm(String message, String clientKey) {
        purgeExpiredSessions();
        long clientSessions = sessions.values().stream()
                .filter(session -> session.clientKey().equals(clientKey))
                .count();
        if (sessions.size() >= maxPendingSessions || clientSessions >= MAX_PENDING_SESSIONS_PER_CLIENT) {
            return Response.rateLimited();
        }
        String sessionId = newSessionId();
        Session session = new Session(newToken(), clock.instant().toEpochMilli(), clientKey);
        sessions.put(sessionId, session);
        return Response.html(Pages.registerPage(session.csrfToken(), message), sessionCookie(sessionId));
    }

    private synchronized Session consumeSession(String sessionId) {
        purgeExpiredSessions();
        return sessions.remove(sessionId);
    }

    public synchronized void purgeExpiredSessions() {
        long now = clock.instant().toEpochMilli();
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now, config.sessionTtlNanos())) {
                iterator.remove();
            }
        }
    }

    private record Session(String csrfToken, long createdMillis, String clientKey) {
        boolean expired(long now, long ttlNanos) {
            return now - createdMillis > ttlNanos / 1_000_000L;
        }
    }
}
