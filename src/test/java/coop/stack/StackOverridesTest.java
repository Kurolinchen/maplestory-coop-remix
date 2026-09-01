package coop.stack;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackOverridesTest {

    @Test
    void returnsOverrideForKnownItem() {
        StackOverrides overrides = new StackOverrides(Map.of(2000000, (short) 300, 2000004, (short) 300));
        assertEquals(Optional.of((short) 300), overrides.maxPerSlot(2000000));
        assertEquals(Optional.of((short) 300), overrides.maxPerSlot(2000004));
        assertEquals(2, overrides.size());
    }

    @Test
    void emptyForUnknownItems() {
        StackOverrides overrides = new StackOverrides(Map.of(2000000, (short) 300));
        assertTrue(overrides.maxPerSlot(9999999).isEmpty());
    }

    @Test
    void emptyInstanceHasNoOverrides() {
        StackOverrides overrides = new StackOverrides(Map.of());
        assertEquals(0, overrides.size());
        assertTrue(overrides.maxPerSlot(2000000).isEmpty());
    }
}
