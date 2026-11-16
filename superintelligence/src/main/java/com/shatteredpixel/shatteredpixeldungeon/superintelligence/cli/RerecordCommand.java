package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.io.File;
import java.io.IOException;

public final class RerecordCommand {

	private RerecordCommand(){}

	public static void main( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] rerecord needs a recording" );
			System.exit( 1 );
			return;
		}
		File file = CliOptions.resolve( new File( args[ 0 ] ));
		CliOptions options = CliOptions.parse( Cli.tail( args ));
		Cli.boot( options );
		if (options.saveTo == null){
			System.err.println( "[ERROR] rerecord needs --save <file>" );
			System.exit( 1 );
			return;
		}

		Replay old;
		try {
			old = ReplayIO.read( file );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read " + file.getPath() + ": " + e.getMessage() );
			System.exit( 1 );
			return;
		}
		if (!old.scenario.isEmpty()){
			System.err.println( "[ERROR] " + file.getName() + " is a scenario recording; scenariocheck rewrites those" );
			System.exit( 1 );
			return;
		}

		Replay fresh = record( old );
		try {
			ReplayIO.write( fresh, options.saveTo );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot write " + options.saveTo.getPath() + ": " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		ReplayIO.Verification result = ReplayIO.verify( fresh, new SPDEnv( ReplayIO.configFor( fresh ), HeadlessGame.install() ));
		if (!result.passed()){
			System.err.println( "[ERROR] the re-recording does not reproduce: "
					+ (result.diverged ? "step " + result.divergedAt + " " + result.divergence : result.unwatchable ));
			System.exit( 2 );
			return;
		}
		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     re-recorded " + fresh.length() + " of " + old.length()
				+ " steps, " + fresh.termination + " at depth " + fresh.depth + ", to " + options.saveTo.getPath()
				+ "; reproduced in " + result.stepsVerified + " steps" );
	}

	private static Replay record( Replay old ){
		EnvConfig config = ReplayIO.configFor( old );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( old.seedText, HeroClass.valueOf( old.heroClass ));
		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), old.heroClass, old.challenges, config.turnLimitPerFloor, config );
		for (Replay.Step step : old.steps){
			if (!env.running() || !env.mode().name().equals( step.mode )) break;
			Action action = Action.valueOf( step.action );
			recorder.record( action, step.slot, env.mode() );
			float reward = (float) env.step( action, step.slot );
			recorder.afterStep( env.heroPosition(), reward, env.lastStepActed() ? 1 : 0 );
		}
		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), old.generation,
				env.running() ? "TRUNCATED" : env.endReason().name() );
		return recorder.replay();
	}
}
