/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License version 3.
*/
package scripting.map;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MapScriptManagerTest {

    @Test
    void cachedScriptRemainsAvailableWithoutBackingFile() throws Exception {
        String scriptPath = "onUserEnter/999999999";
        assertFalse(Files.exists(Path.of("scripts", "map", scriptPath + ".js")));

        MapScriptManager manager = MapScriptManager.getInstance();
        Map<String, Invocable> scripts = scriptCache(manager);
        scripts.put(scriptPath, mock(Invocable.class));
        try {
            assertTrue(manager.hasMapScript(scriptPath));
        } finally {
            scripts.remove(scriptPath);
        }
    }

    @Test
    void existingScriptIsAvailableBeforeItIsCached() {
        assertTrue(MapScriptManager.getInstance().hasMapScript("onUserEnter/200090000"));
        assertFalse(MapScriptManager.getInstance().hasMapScript("onFirstUserEnter/200090000"));
    }

    @Test
    void numericEntryScriptRequiresTheCorrespondingHookScript() {
        MapScriptManager manager = MapScriptManager.getInstance();

        assertTrue(manager.hasEntryScript(200_090_000, "200090000", ""));
        assertFalse(manager.hasEntryScript(200_090_000, "", "200090000"));
        assertFalse(manager.hasEntryScript(100_020_000, "100020000", "100020000"));
        assertTrue(manager.hasEntryScript(100_020_000, "explorationPoint", ""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Invocable> scriptCache(MapScriptManager manager) throws Exception {
        Field field = MapScriptManager.class.getDeclaredField("scripts");
        field.setAccessible(true);
        return (Map<String, Invocable>) field.get(manager);
    }
}
