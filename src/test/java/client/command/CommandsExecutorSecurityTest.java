package client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandsExecutorSecurityTest {
    @Test
    void previouslyUnrankedCommandsAreGatedBehindRank2() {
        assertEquals(2, CommandsExecutor.getInstance().getCommandRank("gachalist"));
        assertEquals(2, CommandsExecutor.getInstance().getCommandRank("loot"));
        assertEquals(2, CommandsExecutor.getInstance().getCommandRank("mobskill"));
    }

    @Test
    void playerCommandsRemainRank0() {
        assertEquals(0, CommandsExecutor.getInstance().getCommandRank("help"));
        assertEquals(0, CommandsExecutor.getInstance().getCommandRank("online"));
        assertEquals(0, CommandsExecutor.getInstance().getCommandRank("companion"));
    }

    @Test
    void privilegedCommandsKeepTheirRanks() {
        assertEquals(1, CommandsExecutor.getInstance().getCommandRank("goto"));
        assertEquals(6, CommandsExecutor.getInstance().getCommandRank("shutdown"));
        assertEquals(6, CommandsExecutor.getInstance().getCommandRank("setgmlevel"));
    }

    @Test
    void unknownCommandHasNoRank() {
        assertEquals(-1, CommandsExecutor.getInstance().getCommandRank("does-not-exist"));
    }
}
