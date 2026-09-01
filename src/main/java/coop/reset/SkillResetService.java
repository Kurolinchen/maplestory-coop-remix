/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.reset;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import constants.skills.Aran;

import java.util.HashMap;
import java.util.Map;

/**
 * Full skill reset with SP refund for fast build testing (Milestone 0.1).
 * Removes every learned skill and refunds spent SP per skillbook, mirroring the SP
 * accounting of {@code AssignSPProcessor.SPAssignAction} (skillbook derived from the
 * skill's job id; beginner skills and Aran auto-learned skills never spent SP).
 */
public final class SkillResetService {
    private SkillResetService() {
    }

    public static void resetSkills(Character player, boolean refundSp) {
        Map<Skill, Character.SkillEntry> learned = new HashMap<>(player.getSkills());
        int[] refund = new int[10];

        for (Map.Entry<Skill, Character.SkillEntry> entry : learned.entrySet()) {
            Skill skill = entry.getKey();
            byte level = entry.getValue().skillevel;
            if (refundSp && level > 0 && spentSp(skill)) {
                int book = GameConstants.getSkillBook(skill.getId() / 10000);
                refund[book] += level;
            }
            player.changeSkillLevel(skill, (byte) -1, -1, -1);
        }

        // Upstream ResetSkillCommand parity: remove the extra movement/combat-step skills
        // that may exist outside the regular skill map iteration.
        if (player.getJob().isA(Job.ARAN1) || player.getJob().isA(Job.LEGEND)) {
            Skill skill = SkillFactory.getSkill(5001005);
            player.changeSkillLevel(skill, (byte) -1, -1, -1);
        } else {
            Skill skill = SkillFactory.getSkill(21001001);
            player.changeSkillLevel(skill, (byte) -1, -1, -1);
        }

        if (refundSp) {
            for (int book = 0; book < refund.length; book++) {
                if (refund[book] > 0) {
                    player.gainSp(refund[book], book, false);
                }
            }
        }
    }

    /**
     * Whether levels in this skill were paid with SP (mirrors SPAssignAction accounting):
     * beginner skills (1000-1002 pattern), PQ/GM-granted skills and Aran auto/quest skills
     * never cost SP — except Aran's FULL_SWING/OVER_SWING, which are SP-leveled.
     */
    private static boolean spentSp(Skill skill) {
        int id = skill.getId();
        if (id % 10000000 > 999 && id % 10000000 < 1003) {
            return false;
        }
        if (GameConstants.isPqSkill(id) || GameConstants.isGMSkills(id)) {
            return false;
        }
        return !GameConstants.isAranSkills(id) || id == Aran.FULL_SWING || id == Aran.OVER_SWING;
    }
}
