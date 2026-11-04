package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.Cli;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainerMain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class InProcessCheck {

	private static final int CHECKS = 2;

	public static void main( String[] args ){
		try {
			check();
		} catch (IOException e){
			fail( "in-process run threw " + e );
		}
		System.exit( GateReport.report( "in-process workers", CHECKS ));
	}

	private static void check() throws IOException {
		File dir = Files.createTempDirectory( "spd-inprocesscheck" ).toFile();
		File replays = new File( dir, "replays" );
		TrainerMain.main( new String[]{ "--workers", "2", "--episodes", "2", "--generations", "1", "--seed", "7",
				"--out", dir.getAbsolutePath(), "--replay-dir", replays.getAbsolutePath() } );

		File[] written = replays.listFiles( ( d, name ) -> name.endsWith( ".dat" ) );
		if (!WorkerPool.inProcess || written == null || written.length == 0){
			fail( "the run was in-process " + WorkerPool.inProcess + " and wrote "
					+ ( written == null ? 0 : written.length ) + " recordings, so nothing below was tested" );
		} else {
			System.out.println( "  two in-process workers trained one generation and wrote " + written.length + " recordings" );
			checkEveryRecordingReproduces( written );
		}
	}

	private static void checkEveryRecordingReproduces( File[] written ) throws IOException {
		Cli.boot( CliOptions.parse( new String[ 0 ] ));
		HeadlessGame game = HeadlessGame.install();
		int steps = 0;
		for (File file : written){
			Replay replay = ReplayIO.read( file );
			ReplayIO.Verification result = ReplayIO.verify( replay, new SPDEnv( ReplayIO.configFor( replay ), game ));
			if (result.diverged || result.unwatchableAt >= 0){
				fail( file.getName() + " written by an in-process worker does not reproduce: "
						+ ( result.diverged ? result.divergence : result.unwatchable ));
				return;
			}
			steps += result.stepsVerified;
		}
		System.out.println( "  all of them reproduce in a fresh game, " + steps + " steps" );
	}
}
