package coop.security;

import java.lang.reflect.Modifier;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmLevelTest {
    @ParameterizedTest
    @CsvSource({
            "-2147483648, 0",
            "-1, 0",
            "0, 0",
            "1, 1",
            "6, 6",
            "7, 6",
            "2147483647, 6"
    })
    void normalizesToSupportedRankRange(int input, int expected) {
        assertEquals(expected, GmLevel.normalize(input));
        assertEquals(input >= 0 && input <= 6, GmLevel.isValid(input));
    }

    @Test
    void onlineRankFieldsAreVisibleAcrossCommandThreads() throws Exception {
        assertTrue(Modifier.isVolatile(Character.class.getDeclaredField("gmLevel").getModifiers()));
        assertTrue(Modifier.isVolatile(Client.class.getDeclaredField("gmlevel").getModifiers()));
    }
}
