package coop.registration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationValidatorTest {
    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void acceptsValidCredentials() {
        RegistrationValidator.Result result = RegistrationValidator.validate("Tester", PASSWORD, PASSWORD);
        assertEquals(RegistrationValidator.Status.OK, result.status());
        assertTrue(result.accepted());
        assertEquals("Tester", result.username());
    }

    @Test
    void acceptsUnderscoreInUsername() {
        assertEquals(RegistrationValidator.Status.OK,
                RegistrationValidator.validate("Test_user", PASSWORD, PASSWORD).status());
    }

    @Test
    void rejectsUsernamePunctuation() {
        for (String username : new String[]{"test-user", "test.user", "test!user", "test user"}) {
            assertEquals(RegistrationValidator.Status.USERNAME_INVALID,
                    RegistrationValidator.validate(username, PASSWORD, PASSWORD).status());
        }
    }

    @Test
    void rejectsShortUsername() {
        assertEquals(RegistrationValidator.Status.USERNAME_INVALID,
                RegistrationValidator.validate("abc", PASSWORD, PASSWORD).status());
    }

    @Test
    void rejectsNonAsciiUsername() {
        assertEquals(RegistrationValidator.Status.USERNAME_INVALID,
                RegistrationValidator.validate("Tästername", PASSWORD, PASSWORD).status());
    }

    @Test
    void rejectsTooLongUsername() {
        assertEquals(RegistrationValidator.Status.USERNAME_INVALID,
                RegistrationValidator.validate("abcdefghijklmn", PASSWORD, PASSWORD).status());
    }

    @Test
    void rejectsShortPassword() {
        assertEquals(RegistrationValidator.Status.PASSWORD_INVALID,
                RegistrationValidator.validate("Tester", "short", "short").status());
    }

    @Test
    void rejectsControlCharactersInPassword() {
        assertEquals(RegistrationValidator.Status.PASSWORD_INVALID,
                RegistrationValidator.validate("Tester", "correct-horse\u0007battery", "correct-horse\u0007battery")
                        .status());
    }

    @Test
    void rejectsMismatchedConfirmation() {
        assertEquals(RegistrationValidator.Status.PASSWORD_MISMATCH,
                RegistrationValidator.validate("Tester", PASSWORD, PASSWORD + "x").status());
    }

    @Test
    void constantTimeEqualsComparesContent() {
        assertTrue(RegistrationValidator.constantTimeEquals("abc", "abc"));
        assertFalse(RegistrationValidator.constantTimeEquals("abc", "abd"));
        assertFalse(RegistrationValidator.constantTimeEquals("abc", "ab"));
    }
}
