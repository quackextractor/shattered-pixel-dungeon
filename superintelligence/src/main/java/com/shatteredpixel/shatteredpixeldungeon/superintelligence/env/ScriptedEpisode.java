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

public class ScriptedEpisode {

	public static class Result {
		public final SPDEnv env;
		public final Replay replay;
		public final RunReport report;

		Result( SPDEnv env, Replay replay, RunReport report ){
			this.env = env;
			this.replay = replay;
			this.report = report;
		}

		public String seedText(){
			return env.seedText();
		}
	}

	private ScriptedEpisode() {}

	public static Result record( EnvConfig config, String seedText, HeroClass hero, int stepLimit,
			int policySeed ){
		SPDEnv env = new SPDEnv( config, com.shatteredpixel.shatteredpixeldungeon.superintelligence
				.headless.HeadlessGame.install() );

		env.reset( seedText, hero );

		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), policySeed );
		ReplayRecorder recorder = new ReplayRecorder();

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