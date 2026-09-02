package coop.registration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormCodecTest {
    @Test
    void encodesAndDecodesRoundTrip() {
        Map<String, String> form = new HashMap<>();
        form.put("username", "Tester");
        form.put("password", "correct horse & battery");
        Map<String, String> decoded = FormCodec.decode(FormCodec.encode(form));
        assertEquals("Tester", decoded.get("username"));
        assertEquals("correct horse & battery", decoded.get("password"));
    }

    @Test
    void rejectsDuplicateFields() {
        byte[] body = "username=a&username=b".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> FormCodec.decode(body));
    }
}

class RegistrationHandlerTest {
    private static final String ORIGIN = "https://dream-ms.duckdns.org";
    private static final String HOST = "dream-ms.duckdns.org";

    private static RegistrationConfig config() {
        return new RegistrationConfig(ORIGIN, "jdbc:mysql://db/cosmic", "user", "secret", "invite-secret",
                8080, 5, TimeUnit.MINUTES.toNanos(15), 20, TimeUnit.MINUTES.toNanos(10), "/tmp");
    }

    private static RegistrationRateLimiter limiter() {
        return new RegistrationRateLimiter(System::nanoTime, 5, TimeUnit.MINUTES.toNanos(15), 20,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
    }

    private static String csrfFrom(String html) {
        int index = html.indexOf("name=\"csrf\" value=\"");
        return html.substring(index + "name=\"csrf\" value=\"".length(), html.indexOf('"', index + 20));
    }

    private record Submission(Response page, byte[] body) {
        String cookie() {
            return page.setCookie().split(";")[0];
        }
    }

    private Submission submission(RegistrationHandler handler, String invite, String username, String password,
                                  String confirmation) {
        Response get = handler.get(ORIGIN, HOST, "203.0.113.9");
        Map<String, String> fields = new HashMap<>();
        fields.put("csrf", csrfFrom(get.body()));
        fields.put("invite", invite);
        fields.put("username", username);
        fields.put("password", password);
        fields.put("confirmation", confirmation);
        return new Submission(get, FormCodec.encode(fields));
    }

    @Test
    void validSubmissionCreatesAnAccount() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.CREATED);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        Submission submission = submission(handler, "invite-secret", "Tester", "correct-horse-battery",
                "correct-horse-battery");
        assertEquals(200, submission.page().status());
        assertTrue(submission.page().setCookie().contains("HttpOnly"));
        assertTrue(submission.page().setCookie().contains("SameSite=Strict"));

        Response post = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                submission.cookie(), "203.0.113.9", submission.body());
        assertEquals(200, post.status());
        assertTrue(post.body().contains("Account erstellt"));
        assertEquals(1, repository.calls);
    }

    @Test
    void duplicateUsernameReturnsNeutralMessage() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.DUPLICATE);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        Submission submission = submission(handler, "invite-secret", "Tester", "correct-horse-battery",
                "correct-horse-battery");
        Response post = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                submission.cookie(), "203.0.113.9", submission.body());
        assertEquals(200, post.status());
        assertTrue(post.body().contains("konnte nicht erstellt werden"));
    }

    @Test
    void unknownSessionIsRejectedAsExpired() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.CREATED);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        Map<String, String> fields = new HashMap<>();
        fields.put("csrf", "wrong-token");
        fields.put("invite", "invite-secret");
        fields.put("username", "Tester");
        fields.put("password", "correct-horse-battery");
        fields.put("confirmation", "correct-horse-battery");
        Response post = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                "coop_reg_session=missing", "203.0.113.9", FormCodec.encode(fields));
        assertEquals(429, post.status());
        assertEquals(0, repository.calls);
    }

    @Test
    void foreignOriginIsRejected() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.CREATED);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        assertEquals(403, handler.get("https://evil.example", HOST, "203.0.113.9").status());
    }

    @Test
    void wrongInviteIsRejectedWithoutCreatingAnAccount() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.CREATED);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        Submission submission = submission(handler, "wrong-invite", "Tester", "correct-horse-battery",
                "correct-horse-battery");
        Response post = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                submission.cookie(), "203.0.113.9", submission.body());
        assertEquals(200, post.status());
        assertTrue(post.body().contains("Einladungscode ist ungültig"));
        assertEquals(0, repository.calls);

        Map<String, String> retryFields = new HashMap<>();
        retryFields.put("csrf", csrfFrom(post.body()));
        retryFields.put("invite", "invite-secret");
        retryFields.put("username", "Tester");
        retryFields.put("password", "correct-horse-battery");
        retryFields.put("confirmation", "correct-horse-battery");
        Response retry = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                post.setCookie().split(";")[0], "203.0.113.9", FormCodec.encode(retryFields));
        assertEquals(200, retry.status());
        assertTrue(retry.body().contains("Account erstellt"));
        assertEquals(1, repository.calls);
    }

    @Test
    void pendingSessionCapBoundsMemory() {
        RegistrationHandler handler = new RegistrationHandler(config(),
                new FakeRepository(AccountCreationResult.CREATED), limiter(), Clock.systemUTC(), 1);
        assertEquals(200, handler.get(ORIGIN, HOST, "203.0.113.9").status());
        assertEquals(429, handler.get(ORIGIN, HOST, "203.0.113.9").status());
    }

    @Test
    void pendingSessionsAreLimitedPerClient() {
        RegistrationHandler handler = new RegistrationHandler(config(),
                new FakeRepository(AccountCreationResult.CREATED), limiter(), Clock.systemUTC(), 10);
        for (int i = 0; i < 4; i++) {
            assertEquals(200, handler.get(ORIGIN, HOST, "203.0.113.9").status());
        }
        assertEquals(429, handler.get(ORIGIN, HOST, "203.0.113.9").status());
        assertEquals(200, handler.get(ORIGIN, HOST, "203.0.113.10").status());
    }

    @Test
    void rateLimitBlocksAfterBurst() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.CREATED);
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                System::nanoTime, 1, TimeUnit.MINUTES.toNanos(15), 20,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter, Clock.systemUTC());
        Submission firstSubmission = submission(handler, "invite-secret", "Tester", "correct-horse-battery",
                "correct-horse-battery");
        Response first = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                firstSubmission.cookie(), "203.0.113.9", firstSubmission.body());
        assertEquals(200, first.status());
        Submission secondSubmission = submission(handler, "invite-secret", "Tester2", "correct-horse-battery",
                "correct-horse-battery");
        Response second = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                secondSubmission.cookie(), "203.0.113.9", secondSubmission.body());
        assertEquals(429, second.status());
    }

    @Test
    void unavailableDatabaseReturnsServiceUnavailable() {
        FakeRepository repository = new FakeRepository(AccountCreationResult.UNAVAILABLE);
        RegistrationHandler handler = new RegistrationHandler(config(), repository, limiter(), Clock.systemUTC());
        Submission submission = submission(handler, "invite-secret", "Tester", "correct-horse-battery",
                "correct-horse-battery");
        Response post = handler.post(ORIGIN, HOST, "application/x-www-form-urlencoded",
                submission.cookie(), "203.0.113.9", submission.body());
        assertEquals(503, post.status());
    }

    private static final class FakeRepository extends AccountRegistrationRepository {
        private final AccountCreationResult result;
        private int calls;

        private FakeRepository(AccountCreationResult result) {
            super(null);
            this.result = result;
        }

        @Override
        public AccountCreationResult create(String username, String rawPassword) {
            calls++;
            return result;
        }

        @Override
        public boolean isReachable() {
            return result != AccountCreationResult.UNAVAILABLE;
        }
    }
}
