package scripting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KerningPqScriptTest {
    private static final ScriptManager SCRIPT_MANAGER = new ScriptManager();

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @Test
    void puzzleSpotsExcludeLeaderAndAllowSoloToStayOffSpots() throws Exception {
        ScriptEngine engine = SCRIPT_MANAGER.load("npc/9020001.js");
        assertNotNull(engine);
        Invocable script = (Invocable) engine;

        assertEquals(0, invokeInt(script, "requiredPuzzleSpots", 1, 6));
        assertEquals(1, invokeInt(script, "requiredPuzzleSpots", 2, 6));
        assertEquals(2, invokeInt(script, "requiredPuzzleSpots", 3, 6));
        assertEquals(3, invokeInt(script, "requiredPuzzleSpots", 4, 6));
    }

    private static int invokeInt(Invocable script, String function, Object... arguments)
            throws ScriptException, NoSuchMethodException {
        return ((Number) script.invokeFunction(function, arguments)).intValue();
    }

    private static class ScriptManager extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
