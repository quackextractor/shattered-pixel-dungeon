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
