package coop.registration;

import org.junit.jupiter.api.Test;

import tools.BCrypt;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BcryptCompatibilityTest {
    @Test
    void generatedHashUsesTheExpectedBcryptRevisionAndCost() {
        String hash = BCrypt.hashpw("correct-horse-battery", BCrypt.gensalt(12));
        assertTrue(hash.startsWith("$2"), "hash must use a bcrypt revision the login path recognizes");
        assertTrue(hash.startsWith("$2y$12$"), "hash must use the revision and cost used by automatic registration");
    }

    @Test
    void generatedHashVerifiesWithTheGameLoginImplementation() {
        String password = "correct-horse-battery";
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        assertTrue(BCrypt.checkpw(password, hash));
    }
}
