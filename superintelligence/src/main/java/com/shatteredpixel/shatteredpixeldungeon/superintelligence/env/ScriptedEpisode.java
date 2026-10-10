/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RunReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

/**
 * One scripted headless episode, recorded.
 *
 * <p>Extracted from {@code Main.rollout} so that {@code rolloutcheck} can assert on what the command
 * actually records without invoking a command line. Two real faults lived in that wiring and neither was
 * reachable from a gate: an empty-seed guard that rejected the flag and its absence alike, making random
 * seeds unreachable, and a recording that stored the seed it was <em>asked</em> for rather than the seed
 * the environment resolved - which on a random episode is a replay nothing can ever verify.
 *
 * <p>The point of extracting rather than duplicating is the whole reason the viewer and the trainer came
 * to disagree in the first place: a second implementation of "run an episode and record it" is a second
 * thing to keep in step. There is now one, and the command is a caller of it.
 */
public class ScriptedEpisode {

	/** What an episode produced. */
	public static class Result {
		public final SPDEnv env;
		public final Replay replay;
		public final RunReport report;

		Result( SPDEnv env, Replay replay, RunReport report ){
			this.env = env;
			this.replay = replay;
			this.report = report;
		}

		/** The seed this episode actually ran on, which is not the one that was requested. */
		public String seedText(){
			return env.seedText();
		}
	}

	private ScriptedEpisode() {}

	/**
	 * Runs one episode under the scripted policy and records it.
	 *
	 * @param config      environment settings; {@code turnLimitPerFloor} bounds the episode
	 * @param seedText    the seed to ask for; empty or null means the environment draws one
	 * @param hero        hero class
	 * @param stepLimit   hard cap on agent steps, or 0 for the per-floor cap alone
	 * @param policySeed  seed for the scripted policy's own choices
	 */
	public static Result record( EnvConfig config, String seedText, HeroClass hero, int stepLimit,
			int policySeed ){
		SPDEnv env = new SPDEnv( config, com.shatteredpixel.shatteredpixeldungeon.superintelligence
				.headless.HeadlessGame.install() );

		env.reset( seedText, hero );

		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), policySeed );
		ReplayRecorder recorder = new ReplayRecorder();

		//begun after the reset, and with the seed the environment resolved rather than the one asked
		//for. On a random episode those differ, and recording the empty request produces a replay that
		//verify cannot rebuild: it draws a different random world and reports a step-0 divergence that
		//looks like a broken seed lock and is not one.
		recorder.begin( env.seedText(), hero.name(), 0, config.turnLimitPerFloor, config );

		RunReport report = new RunReport( env.ledger(), env.seedText() );
		int[] slot = new int[ 1 ];

		while (env.running() && (stepLimit <= 0 || report.scoreSeries().length < stepLimit)){
			Action action = policy.choose( env, slot );
			recorder.record( action, slot[ 0 ], env.mode() );

			float reward = (float) env.step( action, slot[ 0 ] );
			recorder.afterStep( env.heroPosition(), reward );

			report.sample( (float) env.ledger().total(), env.depth() );
		}

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );

		return new Result( env, recorder.replay(), report );
	}
}