/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation version 3 as published by
the Free Software Foundation. You may not use, modify or distribute
this program under any other version of the GNU Affero General Public
License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package scripting.map;

import client.Character;
import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MapScriptManager extends AbstractScriptManager {
    private static final Logger log = LoggerFactory.getLogger(MapScriptManager.class);
    private static final MapScriptManager instance = new MapScriptManager();

    private final Map<String, Invocable> scripts = new ConcurrentHashMap<>();

    public static MapScriptManager getInstance() {
        return instance;
    }

    public void reloadScripts() {
        scripts.clear();
    }

    public boolean hasMapScript(String mapScriptPath) {
        return scripts.containsKey(mapScriptPath)
                || Files.isRegularFile(Path.of("scripts", "map", mapScriptPath + ".js"));
    }

    public boolean hasEntryScript(int mapId, String onUserEnter, String onFirstUserEnter) {
        return isEntryScript(mapId, onUserEnter, "onUserEnter")
                || isEntryScript(mapId, onFirstUserEnter, "onFirstUserEnter");
    }

    private boolean isEntryScript(int mapId, String scriptName, String scriptDirectory) {
        if (scriptName == null || scriptName.isEmpty()) {
            return false;
        }
        return !scriptName.equals(Integer.toString(mapId))
                || hasMapScript(scriptDirectory + "/" + scriptName);
    }

    public boolean runMapScript(Client c, String mapScriptPath, boolean firstUser) {
        if (firstUser) {
            Character chr = c.getPlayer();
            int mapid = chr.getMapId();
            if (chr.hasEntered(mapScriptPath, mapid)) {
                return false;
            } else {
                chr.enteredScript(mapScriptPath, mapid);
            }
        }

        Invocable iv = scripts.get(mapScriptPath);
        if (iv != null) {
            try {
                iv.invokeFunction("start", new MapScriptMethods(c));
                return true;
            } catch (final ScriptException | NoSuchMethodException e) {
                e.printStackTrace();
            }
        }

        try {
            iv = (Invocable) getInvocableScriptEngine("map/" + mapScriptPath + ".js");
            if (iv == null) {
                return false;
            }

            scripts.put(mapScriptPath, iv);
            iv.invokeFunction("start", new MapScriptMethods(c));
            return true;
        } catch (final Exception e) {
            log.error("Error running map script {}", mapScriptPath, e);
        }

        return false;
    }
}
