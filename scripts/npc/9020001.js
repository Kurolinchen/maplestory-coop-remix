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

/**
 * @author: Stereo, Moogra, Ronan
 * @npc: Cloto
 * @map: 1st Accompaniment - KPQ
 * @func: Kerning PQ
 */

var stage1Questions = Array(
    "Here's the question. Collect the same number of coupons as the minimum level required to make the first job advancement as warrior.",
    "Here's the question. Collect the same number of coupons as the minimum amount of STR needed to make the first job advancement as a warrior.",
    "Here's the question. Collect the same number of coupons as the minimum amount of INT needed to make the first job advancement as a magician.",
    "Here's the question. Collect the same number of coupons as the minimum amount of DEX needed to make the first job advancement as a bowman.",
    "Here's the question. Collect the same number of coupons as the minimum amount of DEX needed to make the first job advancement as a thief.",
    "Here's the question. Collect the same number of coupons as the minimum level required to advance to 2nd job.",
    "Here's the question. Collect the same number of coupons as the minimum level required to make the first job advancement as a magician.");
var stage1Answers = Array(10, 35, 20, 25, 25, 30, 8);

const Rectangle = Java.type('java.awt.Rectangle');
var stage2Rects = Array(new Rectangle(-755, -132, 4, 218), new Rectangle(-721, -340, 4, 166), new Rectangle(-586, -326, 4, 150), new Rectangle(-483, -181, 4, 222));
var stage3Rects = Array(new Rectangle(608, -180, 140, 50), new Rectangle(791, -117, 140, 45),
    new Rectangle(958, -180, 140, 50), new Rectangle(876, -238, 140, 45),
    new Rectangle(702, -238, 140, 45));
var stage4Rects = Array(new Rectangle(910, -236, 35, 5), new Rectangle(877, -184, 35, 5),
    new Rectangle(946, -184, 35, 5), new Rectangle(845, -132, 35, 5),
    new Rectangle(910, -132, 35, 5), new Rectangle(981, -132, 35, 5));

var stage2Combos = Array(Array(0, 1, 1, 1), Array(1, 0, 1, 1), Array(1, 1, 0, 1), Array(1, 1, 1, 0));
var stage3Combos = Array(Array(0, 0, 1, 1, 1), Array(0, 1, 0, 1, 1), Array(0, 1, 1, 0, 1),
    Array(0, 1, 1, 1, 0), Array(1, 0, 0, 1, 1), Array(1, 0, 1, 0, 1),
    Array(1, 0, 1, 1, 0), Array(1, 1, 0, 0, 1), Array(1, 1, 0, 1, 0),
    Array(1, 1, 1, 0, 0));
var stage4Combos = Array(Array(0, 0, 0, 1, 1, 1), Array(0, 0, 1, 0, 1, 1), Array(0, 0, 1, 1, 0, 1),
    Array(0, 0, 1, 1, 1, 0), Array(0, 1, 0, 0, 1, 1), Array(0, 1, 0, 1, 0, 1),
    Array(0, 1, 0, 1, 1, 0), Array(0, 1, 1, 0, 0, 1), Array(0, 1, 1, 0, 1, 0),
    Array(0, 1, 1, 1, 0, 0), Array(1, 0, 0, 0, 1, 1), Array(1, 0, 0, 1, 0, 1),
    Array(1, 0, 0, 1, 1, 0), Array(1, 0, 1, 0, 0, 1), Array(1, 0, 1, 0, 1, 0),
    Array(1, 0, 1, 1, 0, 0), Array(1, 1, 0, 0, 0, 1), Array(1, 1, 0, 0, 1, 0),
    Array(1, 1, 0, 1, 0, 0), Array(1, 1, 1, 0, 0, 0));

function clearStage(stage, eim, curMap) {
    eim.setProperty(stage + "stageclear", "true");
    eim.showClearEffect(true);

    eim.linkToNextStage(stage, "kpq", curMap);  //opens the portal to the next map
}

// coop 0.1b (Early Game Remix, DECISIONS.md D11): the upstream stage logic
// hardcodes combinations with exactly THREE occupied spots, so a solo runner
// would be permanently stuck at stage 2. These stages are pure "stand on the
// right spots" puzzles, so the correct fix is to scale the number of required
// spots to the non-leader party members - no client patch and no content is skipped.
function requiredPuzzleSpots(playerCount, spotCount) {
    return Math.max(0, Math.min(playerCount - 1, spotCount));
}

function combosForTeamSize(spotCount, teamSize) {
    var result = [];
    var total = 1 << spotCount;
    for (var mask = 0; mask < total; mask++) {
        var bits = 0;
        for (var i = 0; i < spotCount; i++) {
            if (mask & (1 << i)) {
                bits++;
            }
        }
        if (bits !== teamSize) {
            continue;
        }
        var combo = [];
        for (var j = 0; j < spotCount; j++) {
            combo.push((mask & (1 << j)) ? 1 : 0);
        }
        result.push(combo);
    }
    return result;
}

// Stage instructions must describe the CURRENT team: upstream hardcodes "3",
// which is wrong for a solo runner (DECISIONS.md D11).
function stageIntroText(eim, nthtext, nthobj, nthverb, nthpos, areaRects) {
    var n = requiredPuzzleSpots(eim.getPlayerCount(), areaRects.length);
    return "Hi. Welcome to the " + nthtext + " stage. Next to me, you'll see a number of " + nthobj
            + ". Out of these " + nthobj + ", #b" + n + " are connected to the portal that sends you to the next stage#k."
            + " All you need to do is have #b" + n + " party member(s) find the correct " + nthobj + " and " + nthverb + " on them.#k\r\n"
            + "BUT, it doesn't count as an answer if you " + nthpos + "; please be near the middle of the " + nthobj
            + " to be counted as a correct answer. Also, only " + n + " member(s) of your party are allowed on the " + nthobj + "."
            + " Once they are " + nthverb + "ing on them, the leader of the party must #bdouble-click me to check and see if the answer's correct or not#k."
            + " Now, find the right " + nthobj + " to " + nthverb + " on!";
}

function rectangleStages(eim, property, areaCombos, areaRects) {
    var teamSize = requiredPuzzleSpots(eim.getPlayerCount(), areaRects.length);
    var combos = combosForTeamSize(areaRects.length, teamSize);
    if (combos.length === 0) {
        combos = areaCombos;
    }

    var c = eim.getProperty(property);
    if (c == null) {
        c = Math.floor(Math.random() * combos.length);
        eim.setProperty(property, c.toString());
    } else {
        c = parseInt(c);
        // The team size can change if someone leaves; a stale index would then
        // point outside the current combination list, so re-roll it.
        if (isNaN(c) || c < 0 || c >= combos.length) {
            c = Math.floor(Math.random() * combos.length);
            eim.setProperty(property, c.toString());
        }
    }

    // get player placement
    var players = eim.getPlayers();
    var playerPlacement = [0, 0, 0, 0, 0, 0];

    for (var i = 0; i < eim.getPlayerCount(); i++) {
        for (var j = 0; j < areaRects.length; j++) {
            if (areaRects[j].contains(players.get(i).getPosition())) {
                playerPlacement[j] += 1;
                break;
            }
        }
    }

    var curCombo = combos[c];
    var accept = true;
    for (var j = 0; j < curCombo.length; j++) {
        if (curCombo[j] != playerPlacement[j]) {
            accept = false;
            break;
        }
    }

    return accept;
}

var status = -1;
var eim;

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    eim = cm.getEventInstance();

    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && status == 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            var curMap = cm.getMapId();
            var stage = curMap - 103000800 + 1;
            if (eim.getProperty(stage.toString() + "stageclear") != null) {
                if (stage < 5) {
                    cm.sendNext("Please hurry on to the next stage, the portal opened!");
                    cm.dispose();
                } else {
                    cm.sendNext("Incredible! You cleared all the stages to get to this point. Here's a small prize for your job well done. Before you accept it, however, please make sure your use and etc. inventories have empty slots available.");
                }
            } else if (curMap == 103000800) {   // stage 1
                // coop 0.1b (DECISIONS.md D11): a solo entrant is always the
                // event leader, so upstream sends them to the "collect N
                // passes" branch - which demands a pass that only the quiz
                // branch below ever grants. Solo runners therefore stay in the
                // quiz branch and spend the pass themselves.
                var solo = eim.getPlayerCount() <= 1;

                if (solo && cm.itemQuantity(4001008) >= 1) {
                    cm.sendNext("That's the pass you earned! Congratulations on clearing the stage! I'll make the portal that sends you to the next stage. There's a time limit on getting there, so please hurry.");
                    clearStage(stage, eim, curMap);
                    eim.gridClear();
                    cm.gainItem(4001008, -1);
                } else if (cm.isEventLeader() && !solo) {
                    var numpasses = eim.getPlayerCount() - 1;   // minus leader

                    if (cm.hasItem(4001008, numpasses)) {
                        cm.sendNext("You gathered up " + numpasses + " passes! Congratulations on clearing the stage! I'll make the portal that sends you to the next stage. There's a time limit on getting there, so please hurry. Best of luck to you all!");
                        clearStage(stage, eim, curMap);
                        eim.gridClear();
                        cm.gainItem(4001008, -numpasses);
                    } else {
                        cm.sendNext("I'm sorry, but you are short on the number of passes. You need to give me the right number of passes; it should be the number of members of your party minus the leader, in this case the total of " + numpasses + " to clear the stage. Tell your party members to solve the questions, gather up the passes, and give them to you.");
                    }
                } else {
                    var data = eim.gridCheck(cm.getPlayer());

                    if (data == 0) {
                        if (solo) {
                            cm.sendNext("You already answered the question, but you no longer carry the pass. Re-enter the quest to try again.");
                        } else {
                            cm.sendNext("Thanks for bringing me the coupons. Please hand the pass to your party leader to continue.");
                        }
                    } else if (data == -1) {
                        data = Math.floor(Math.random() * stage1Questions.length) + 1;   //data will be counted from 1
                        eim.gridInsert(cm.getPlayer(), data);

                        var question = stage1Questions[data - 1];
                        cm.sendNext(question);
                    } else {
                        var answer = stage1Answers[data - 1];

                        if (cm.itemQuantity(4001007) == answer) {
                            cm.sendNext("That's the right answer! For that you have just received a #bpass#k. Please hand it to the leader of the party.");
                            cm.gainItem(4001007, -answer);
                            cm.gainItem(4001008, 1);
                            eim.gridInsert(cm.getPlayer(), 0);
                        } else {
                            var question = stage1Questions[eim.gridCheck(cm.getPlayer()) - 1];
                            cm.sendNext("I'm sorry, but that is not the right answer!\r\n" + question);
                        }
                    }
                }

                cm.dispose();
            } else if (curMap == 103000801) {   // stage 2
                var stgProperty = "stg2Property";
                var stgCombos = stage2Combos;
                var stgAreas = stage2Rects;

                var nthtext = "2nd", nthobj = "ropes", nthverb = "hang", nthpos = "hang on the ropes too low";
                var nextStgId = 103000802;

                if (!eim.isEventLeader(cm.getPlayer())) {
                    cm.sendOk("Follow the instructions given by your party leader to proceed through this stage.");
                } else if (eim.getProperty(stgProperty) == null) {
                    cm.sendNext(stageIntroText(eim, nthtext, nthobj, nthverb, nthpos, stgAreas));
                    var c = Math.floor(Math.random() * combosForTeamSize(stgAreas.length,
                            requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length)).length);
                    eim.setProperty(stgProperty, c.toString());
                } else {
                    var accept = rectangleStages(eim, stgProperty, stgCombos, stgAreas);

                    if (accept) {
                        clearStage(stage, eim, curMap);
                        cm.sendNext("Please hurry on to the next stage, the portal opened!");
                    } else {
                        eim.showWrongEffect();
                        var requiredSpots = requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length);
                        cm.sendNext("It looks like you haven't found the " + requiredSpots + " " + nthobj + " just yet. Please think of a different combination of " + nthobj + ". Only " + requiredSpots + " are allowed to " + nthverb + " on " + nthobj + ", and if you " + nthpos + " it may not count as an answer, so please keep that in mind. Keep going!");
                    }
                }

                cm.dispose();
            } else if (curMap == 103000802) {
                var stgProperty = "stg3Property";
                var stgCombos = stage3Combos;
                var stgAreas = stage3Rects;

                var nthtext = "3rd", nthobj = "platforms", nthverb = "stand", nthpos = "stand too close to the edges";
                var nextStgId = 103000803;

                if (!eim.isEventLeader(cm.getPlayer())) {
                    cm.sendOk("Follow the instructions given by your party leader to proceed through this stage.");
                } else if (eim.getProperty(stgProperty) == null) {
                    cm.sendNext(stageIntroText(eim, nthtext, nthobj, nthverb, nthpos, stgAreas));
                    var c = Math.floor(Math.random() * combosForTeamSize(stgAreas.length,
                            requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length)).length);
                    eim.setProperty(stgProperty, c.toString());
                } else {
                    var accept = rectangleStages(eim, stgProperty, stgCombos, stgAreas);

                    if (accept) {
                        clearStage(stage, eim, curMap);
                        cm.sendNext("Please hurry on to the next stage, the portal opened!");
                    } else {
                        eim.showWrongEffect();
                        var requiredSpots = requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length);
                        cm.sendNext("It looks like you haven't found the " + requiredSpots + " " + nthobj + " just yet. Please think of a different combination of " + nthobj + ". Only " + requiredSpots + " are allowed to " + nthverb + " on " + nthobj + ", and if you " + nthpos + " it may not count as an answer, so please keep that in mind. Keep going!");
                    }
                }

                cm.dispose();
            } else if (curMap == 103000803) {
                var stgProperty = "stg4Property";
                var stgCombos = stage4Combos;
                var stgAreas = stage4Rects;

                var nthtext = "4th", nthobj = "barrels", nthverb = "stand", nthpos = "stand too close to the edges";
                var nextStgId = 103000804;

                if (!eim.isEventLeader(cm.getPlayer())) {
                    cm.sendOk("Follow the instructions given by your party leader to proceed through this stage.");
                } else if (eim.getProperty(stgProperty) == null) {
                    cm.sendNext(stageIntroText(eim, nthtext, nthobj, nthverb, nthpos, stgAreas));
                    var c = Math.floor(Math.random() * combosForTeamSize(stgAreas.length,
                            requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length)).length);
                    eim.setProperty(stgProperty, c.toString());
                } else {
                    var accept = rectangleStages(eim, stgProperty, stgCombos, stgAreas);

                    if (accept) {
                        clearStage(stage, eim, curMap);
                        cm.sendNext("Please hurry on to the next stage, the portal opened!");
                    } else {
                        eim.showWrongEffect();
                        var requiredSpots = requiredPuzzleSpots(eim.getPlayerCount(), stgAreas.length);
                        cm.sendNext("It looks like you haven't found the " + requiredSpots + " " + nthobj + " just yet. Please think of a different combination of " + nthobj + ". Only " + requiredSpots + " are allowed to " + nthverb + " on " + nthobj + ", and if you " + nthpos + " it may not count as an answer, so please keep that in mind. Keep going!");
                    }
                }

                cm.dispose();
            } else if (curMap == 103000804) {
                if (eim.isEventLeader(cm.getPlayer())) {
                    if (cm.haveItem(4001008, 10)) {
                        cm.sendNext("Here's the portal that leads you to the last, bonus stage. It's a stage that allows you to defeat regular monsters a little easier. You'll be given a set amount of time to hunt as much as possible, but you can always leave the stage in the middle of it through the NPC. Again, congratulations on clearing all the stages. Let your party talk to me to receive their prizes as they are allowed to pass to the bonus stage. Take care...");
                        cm.gainItem(4001008, -10);

                        clearStage(stage, eim, curMap);
                        eim.clearPQ();
                    } else {
                        cm.sendNext("Hello. Welcome to the 5th and final stage. Walk around the map and you'll be able to find some Boss monsters. Defeat all of them, gather up #bthe passes#k, and please get them to me. Once you earn your pass, the leader of your party will collect them, and then get them to me once the #bpasses#k are gathered up. The monsters may be familiar to you, but they may be much stronger than you think, so please be careful. Good luck!");
                    }
                } else {
                    cm.sendNext("Welcome to the 5th and final stage.  Walk around the map and you will be able to find some Boss monsters.  Defeat them all, gather up the #bpasses#k, and #bgive them to your leader#k.  Once you are done, return to me to collect your reward.");
                }

                cm.dispose();
            }
        } else if (status == 1) {
            if (!eim.giveEventReward(cm.getPlayer())) {
                cm.sendNext("Please make room on your inventory first!");
            } else {
                cm.warp(103000805, "st00");
            }

            cm.dispose();
        }
    }
}
