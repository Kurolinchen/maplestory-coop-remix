package coop.reset;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Aran;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillResetServiceTest {

    private MockedStatic<SkillFactory> skillFactory;
    private final Map<Integer, Skill> skillCache = new HashMap<>();

    @BeforeEach
    void stubSkillFactory() {
        // keep unit tests WZ-free: SkillResetService's upstream parity branch reads SkillFactory.
        // The same id must always resolve to the same mock instance, otherwise verify() cannot match.
        skillFactory = mockStatic(SkillFactory.class);
        skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                .thenAnswer(invocation -> skillCache.computeIfAbsent(invocation.getArgument(0),
                        SkillResetServiceTest::skill));
    }

    @AfterEach
    void tearDown() {
        skillFactory.close();
        skillCache.clear();
    }

    private static Skill skill(int id) {
        Skill skill = mock(Skill.class);
        lenient().when(skill.getId()).thenReturn(id);
        return skill;
    }

    private static Character.SkillEntry entry(int level) {
        return new Character.SkillEntry((byte) level, level, -1);
    }

    private Character playerWith(Map<Skill, Character.SkillEntry> skills) {
        Character player = mock(Character.class);
        when(player.getSkills()).thenReturn(skills);
        when(player.getJob()).thenReturn(Job.FP_WIZARD);
        return player;
    }

    @Test
    void removesAllLearnedSkills() {
        Skill fireArrow = skill(2201005);
        Skill poisonBreath = skill(2111003);
        Character player = playerWith(Map.of(
                fireArrow, entry(20),
                poisonBreath, entry(30)));

        SkillResetService.resetSkills(player, false);

        verify(player).changeSkillLevel(fireArrow, (byte) -1, -1, -1);
        verify(player).changeSkillLevel(poisonBreath, (byte) -1, -1, -1);
        verify(player, never()).gainSp(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void refundsSpIntoTheSkillbookOfEachSkill() {
        // job 220 -> skillbook 0; job 2211 -> skillbook 2 (Evan books)
        Skill book0Skill = skill(2201005);
        Skill book2Skill = skill(22111001);
        Character player = playerWith(Map.of(
                book0Skill, entry(20),
                book2Skill, entry(15)));

        SkillResetService.resetSkills(player, true);

        verify(player).gainSp(20, 0, false);
        verify(player).gainSp(15, 2, false);
    }

    @Test
    void doesNotRefundSpThatWasNeverSpent() {
        // beginner skill 1001, Aran auto-learned skills and PQ skills never cost SP
        Skill beginner = skill(1001);
        Skill aranAuto = skill(Aran.DOUBLE_SWING);
        Skill pqSkill = skill(20000014);
        Skill normal = skill(2201005);
        Character player = playerWith(Map.of(
                beginner, entry(3),
                aranAuto, entry(1),
                pqSkill, entry(2),
                normal, entry(10)));

        SkillResetService.resetSkills(player, true);

        verify(player).gainSp(10, 0, false);
        verify(player, never()).gainSp(3, 0, false);
    }

    @Test
    void refundsAranSpLeveledSwings() {
        // unlike other Aran skills, FULL_SWING/OVER_SWING are leveled with SP
        Skill fullSwing = skill(Aran.FULL_SWING);
        Skill overSwing = skill(Aran.OVER_SWING);
        Character player = playerWith(Map.of(
                fullSwing, entry(20),
                overSwing, entry(10)));

        SkillResetService.resetSkills(player, true);

        verify(player).gainSp(30, 0, false);
    }

    @Test
    void ignoresZeroLevelEntriesForRefundButStillRemovesThem() {
        Skill zeroLevel = skill(2201005);
        Character player = playerWith(new HashMap<>(Map.of(zeroLevel, entry(0))));

        SkillResetService.resetSkills(player, true);

        verify(player).changeSkillLevel(zeroLevel, (byte) -1, -1, -1);
        verify(player, never()).gainSp(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void removesTheUpstreamExtraSkillForNonAranJobs() {
        Character player = playerWith(Map.of());
        Skill combatStep = SkillFactory.getSkill(21001001);

        SkillResetService.resetSkills(player, false);

        verify(player).changeSkillLevel(combatStep, (byte) -1, -1, -1);
    }

    @Test
    void removesTheUpstreamExtraSkillForAranJobs() {
        Character player = mock(Character.class);
        when(player.getSkills()).thenReturn(Map.of());
        when(player.getJob()).thenReturn(Job.ARAN1);
        Skill dash = SkillFactory.getSkill(5001005);

        SkillResetService.resetSkills(player, false);

        verify(player).changeSkillLevel(dash, (byte) -1, -1, -1);
    }
}
