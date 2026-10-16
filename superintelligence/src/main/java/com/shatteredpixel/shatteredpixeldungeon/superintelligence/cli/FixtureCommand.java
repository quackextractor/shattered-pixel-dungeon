package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.DropFixture;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.PressFixture;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.util.function.ToIntFunction;

public final class FixtureCommand {

	private FixtureCommand(){}

	private interface FixtureWriter {
		Replay write( EnvConfig config, File file ) throws IOException;
	}

	public static void dropfixture( String[] args ){
		writeFixture( "dropfixture", args, DropFixture::recordAndWrite,
				replay -> firstStep( replay, "DROP", "SLOT" ),
				"DROP in SLOT" );
	}


	public static void pressfixture( String[] args ){
		writeFixture( "pressfixture", args, PressFixture::recordAndWrite,
				replay -> firstStep( replay, "USE", "TARGETING" ),
				"throw resolved in TARGETING" );
	}


	private static void writeFixture( String name, String[] args,
			FixtureWriter writer, ToIntFunction< Replay > witness, String covers ){
		CliOptions options = CliOptions.parse( args );
		Cli.boot( options );

		if (options.saveTo == null){
			System.err.println( "[ERROR] " + name + " needs --save <file>; it writes the recording rather"
					+ " than reporting one" );
			System.exit( 1 );
			return;
		}

		EnvConfig config = Cli.configFor( options );

		Replay replay;
		try {
			replay = writer.write( config, options.saveTo );
		} catch (IllegalStateException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return;
		} catch (IOException e){
			System.err.println( "[ERROR] cannot write the fixture to " + options.saveTo.getPath()
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		int covered = witness.applyAsInt( replay );
		if (covered < 0){
			System.err.println( "[ERROR] the fixture recorded no " + covers + ", so it is a recording that"
					+ " would pass every viewer gate while covering nothing. Refusing to write it." );
			System.exit( 1 );
			return;
		}

		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), HeadlessGame.install() );
		ReplayIO.Verification result = ReplayIO.verify( replay, env );

		if (!result.passed()){
			System.err.println( "[ERROR] the fixture does not reproduce, so it is not a fixture:"
					+ ( result.diverged ? " diverged at step " + result.divergedAt + " - "
						+ result.divergence : result.unwatchable ) );
			System.exit( 1 );
			return;
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     wrote " + replay.length()
				+ " steps, first " + covers + " at step " + covered + ", to " + options.saveTo.getPath()
				+ "; reproduced in " + result.stepsVerified + " steps" );
	}


	private static int firstStep( Replay replay, String action, String mode ){
		for (int i = 0; i < replay.steps.size(); i++){
			Replay.Step step = replay.steps.get( i );
			if (step.action.equals( action ) && step.mode.equals( mode )) return i;
		}
		return -1;
	}
}