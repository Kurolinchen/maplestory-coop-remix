/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

/**
 * Early Game Remix (coop 0.1b): make levels 1-10 less of a slog by letting
 * beginners actually use their three beginner skills early.
 *
 * <p>Upstream beginners start without spendable SP, so Three Snails, Recovery
 * and Nimble Feet stay at level 0 through the slowest part of the game. This
 * service adds a configurable SP bonus at character creation for beginner jobs
 * only, so the choice of which skill to raise is still the player's.
 *
 * <p>All methods are pure so the policy can be unit tested without a world.
 */
public final class BeginnerStart {
    private BeginnerStart() {
    }

    /**
     * Beginner jobs that should receive the creation SP bonus.
     *
     * <p>Beginner (0), Noblesse (1000) and Legend (2000) are the three novice
     * creators; veteran creators (which start above level 1) are deliberately
     * excluded because they already have a class.
     */
    public static boolean isBeginnerJob(int jobId) {
        return jobId == 0 || jobId == 1000 || jobId == 2000;
    }

    /** SP to add at creation for this job. Never negative. */
    public static int spBonusFor(int jobId, int configuredBonus) {
        if (!isBeginnerJob(jobId)) {
            return 0;
        }
        return Math.max(0, configuredBonus);
    }
}
