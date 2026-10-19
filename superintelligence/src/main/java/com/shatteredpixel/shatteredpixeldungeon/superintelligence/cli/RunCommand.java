package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;

public final class RunCommand {

	private RunCommand(){}

	public static class Result {
		public int exitCode;
		public String refusal;
		public String seedText = "";
		public String heroClass = "";
		public int steps;
		public int turns;
		public int turnLimitPerFloor;
		public File savedTo;

		public boolean refused(){ return exitCode != 0; }
	}

	public static void main( String[] args ){
		System.exit( execute( CliOptions.parse( args )).exitCode );
	}

	public static Result execute( CliOptions options ){
		Result result = new Result();

		if ( options.seedProvided && (options.seed == null || options.seed.trim().isEmpty()) ){
			result.exitCode = 1;
			result.refusal = "--seed was given but empty. Omit the flag entirely for a "
					+ "random seed, which is then recorded in the replay.";
			System.err.println( "[ERROR] " + result.refusal );
			return result;
		}

		ResourceStats.Interval timing = ResourceStats.start();

		Cli.boot( options );

		EnvConfig config = Cli.configFor( options );
		if (options.maxTurns > 0) config.turnLimitPerFloor = options.maxTurns;

		ResourceStats.Interval simulation = ResourceStats.start();
		long start = System.nanoTime();

		ScriptedEpisode.Result episode =
				ScriptedEpisode.record( config, options.seed, options.hero, 0, options.seed.hashCode() );

		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		simulation.stop();
		timing.stop();

		result.seedText = episode.env.seedText();
		result.heroClass = episode.replay.heroClass;
		result.steps = episode.replay.length();
		result.turns = episode.env.turnsTotal();
		result.turnLimitPerFloor = episode.env.config().turnLimitPerFloor;

		System.out.println();
		System.out.println( episode.report.render( 72 ) );
		System.out.println( episode.report.renderScoreOverFloors( 72 ) );

		System.out.println();
		System.out.println( Ansi.wrap( "outcome", Ansi.DIM ) + "  " + episode.env.endReason()
				+ (episode.env.truncated() ? " (truncated)" : "") );
		System.out.println( Ansi.wrap( "simulation", Ansi.DIM ) + " "
				+ String.format( "%d turns in %.2f s (%,.0f turns/s) on %.2f cores",
				episode.env.turnsTotal(), simulation.wallSeconds(),
				ResourceStats.stepsPerSecond( episode.env.turnsTotal(), simulation.wallSeconds() ),
				Math.max( 0, simulation.coresUsed() ) ) );

		System.out.println();
		System.out.println( ResourceStats.renderSummary( "resources (whole command)",
				timing, episode.env.turnsTotal() ) );

		if (options.saveTo != null){
			try {
				Replay recording = episode.replay;
				ReplayIO.write( recording, options.saveTo );
				result.savedTo = options.saveTo;
				System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   wrote "
						+ recording.length() + " steps to "
						+ com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer
						.describe( options.saveTo ) );
			} catch (IOException e){
				result.exitCode = 1;
				result.refusal = "cannot write the replay to "
						+ com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer
						.describe( options.saveTo )
						+ " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
				System.err.println( "[ERROR] " + result.refusal );
			}
		}

		return result;
	}
}