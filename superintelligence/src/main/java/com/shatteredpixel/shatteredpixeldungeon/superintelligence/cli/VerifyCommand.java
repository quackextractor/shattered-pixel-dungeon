package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RunReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;

public final class VerifyCommand {

	private VerifyCommand(){}

	public static void main( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] replay needs a file" );
			System.exit( 1 );
			return;
		}

		File file = CliOptions.resolve( new File( args[ 0 ] ) );
		CliOptions options = CliOptions.parse( Cli.tail( args ) );
		Cli.boot( options );

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read " + file.getPath() + " ("
					+ e.getClass().getSimpleName() + "): " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		if ( replay.seedText == null || replay.seedText.trim().isEmpty() ){
			System.err.println( "[ERROR] " + file.getName() + " records no seed (header field 'seed=' is empty)."
					+ " Its world cannot be rebuilt, so it cannot be verified. Re-record with a seed." );
			System.exit( 1 );
			return;
		}

		System.out.println( "verify: seed=" + replay.seedText
				+ " hero=" + replay.heroClass
				+ " steps=" + replay.steps.size()
				+ " recorded score=" + replay.score );

		HeadlessGame game = HeadlessGame.install();
		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), game );

		long start = System.nanoTime();
		ReplayIO.Verification result = ReplayIO.verify( replay, env );
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		RunReport report = new RunReport( env.ledger(), replay.seedText );

		System.out.println();
		System.out.println( report.render( 72 ) );
		System.out.println();

		if (result.diverged){
			System.err.println( "[ERROR] replay diverged at step " + result.divergedAt
					+ " of " + replay.steps.size() );
			System.err.println( "        " + result.divergence );
			System.err.println( "        action " + replay.steps.get( result.divergedAt ).action
					+ " slot " + replay.steps.get( result.divergedAt ).slot
					+ " mode " + replay.steps.get( result.divergedAt ).mode );
			System.exit( 2 );
			return;
		}

		if (result.unwatchableAt >= 0){
			System.err.println( "[ERROR] replay reproduced in " + result.stepsVerified
					+ " steps but is not watchable:" );
			System.err.println( "        " + result.unwatchable );
			System.err.println( "        A recording whose slot index cannot be resolved against its own"
					+ " bindings verifies on whatever else it carries. Reproducing is not the same claim"
					+ " as being playable, and this file only ever made the first one." );
			System.exit( 3 );
			return;
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     replay reproduced in "
				+ result.stepsVerified + " steps, " + elapsedMs + " ms" );
		System.out.println( Ansi.wrap( "score", Ansi.DIM ) + "    recorded "
				+ replay.score + ", reproduced " + result.score
				+ (Math.abs( replay.score - result.score ) < 1e-6 ? "" : "  [WARN] differs") );
	}
}