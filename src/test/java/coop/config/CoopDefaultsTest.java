package coop.config;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopDefaultsTest {

    private CoopConfig configWith(int charSlots, int maxCharSlots, int invSlots, int storageSlots, int storageCap) {
        CoopConfig cfg = new CoopConfig();
        cfg.default_character_slots = charSlots;
        cfg.max_character_slots = maxCharSlots;
        cfg.default_inventory_slots = invSlots;
        cfg.default_storage_slots = storageSlots;
        cfg.storage_slot_cap = storageCap;
        return cfg;
    }

    @Test
    void saneValuesPassThrough() {
        CoopConfig cfg = configWith(15, 15, 32, 16, 96);
        assertEquals(15, CoopDefaults.defaultCharacterSlots(cfg));
        assertEquals(15, CoopDefaults.maxCharacterSlots(cfg));
        assertEquals(32, CoopDefaults.defaultInventorySlots(cfg));
        assertEquals(16, CoopDefaults.defaultStorageSlots(cfg));
        assertEquals(96, CoopDefaults.storageSlotCap(cfg));
    }

    @Test
    void characterSlotsClampToByteRange() {
        assertEquals(1, CoopDefaults.defaultCharacterSlots(configWith(0, 15, 32, 16, 96)));
        assertEquals(127, CoopDefaults.defaultCharacterSlots(configWith(999, 999, 32, 16, 96)));
    }

    @Test
    void maxCharacterSlotsNeverBelowDefault() {
        assertEquals(15, CoopDefaults.maxCharacterSlots(configWith(15, 3, 32, 16, 96)));
        assertEquals(20, CoopDefaults.maxCharacterSlots(configWith(15, 20, 32, 16, 96)));
    }

    @Test
    void inventorySlotsClampToInventoryLimits() {
        assertEquals(4, CoopDefaults.defaultInventorySlots(configWith(15, 15, 1, 16, 96)));
        assertEquals(96, CoopDefaults.defaultInventorySlots(configWith(15, 15, 500, 16, 96)));
    }

    @Test
    void storageSlotsClampAgainstCap() {
        // default above cap is reduced to the cap
        assertEquals(48, CoopDefaults.defaultStorageSlots(configWith(15, 15, 32, 999, 48)));
        // cap below the safe minimum of 48 is raised
        assertEquals(48, CoopDefaults.storageSlotCap(configWith(15, 15, 32, 16, 4)));
        // default below minimum is raised
        assertEquals(4, CoopDefaults.defaultStorageSlots(configWith(15, 15, 32, 0, 96)));
    }

    @Test
    void clampIsBounded() {
        assertEquals(5, CoopDefaults.clamp(5, 1, 10));
        assertEquals(1, CoopDefaults.clamp(-3, 1, 10));
        assertEquals(10, CoopDefaults.clamp(42, 1, 10));
    }

    @Test
    void liveConfigIsPresentAndSane() {
        // config.yaml ships the coop block since milestone 0.1
        assertEquals(15, CoopDefaults.defaultCharacterSlots());
        assertEquals(32, CoopDefaults.defaultInventorySlots());
        assertEquals(16, CoopDefaults.defaultStorageSlots());
    }

    @Test
    void yamlBlockParsesIntoCoopConfigIncludingTheOverrideMap() throws Exception {
        String yaml = """
                default_character_slots: 12
                max_character_slots: 20
                default_inventory_slots: 36
                default_storage_slots: 24
                storage_slot_cap: 72
                expedition_min_size:
                    ZAKUM: 3
                    HORNTAIL: 1
                """;
        CoopConfig cfg = new YamlReader(new StringReader(yaml)).read(CoopConfig.class);

        assertEquals(12, cfg.default_character_slots);
        assertEquals(20, cfg.max_character_slots);
        assertEquals(36, cfg.default_inventory_slots);
        assertEquals(24, cfg.default_storage_slots);
        assertEquals(72, cfg.storage_slot_cap);
        assertEquals(Map.of("ZAKUM", 3, "HORNTAIL", 1), cfg.expedition_min_size);
    }

    @Test
    void absentYamlKeysKeepFieldDefaults() throws Exception {
        CoopConfig cfg = new YamlReader(new StringReader("default_character_slots: 9\n")).read(CoopConfig.class);

        assertEquals(9, cfg.default_character_slots);
        assertEquals(15, cfg.max_character_slots);
        assertEquals(32, cfg.default_inventory_slots);
        assertEquals(16, cfg.default_storage_slots);
        assertEquals(96, cfg.storage_slot_cap);
        assertEquals(Map.of(), cfg.expedition_min_size);
    }
}
