package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngTrace;

import java.io.File;
import java.io.IOException;

public final class RngTraceCommand {

	private RngTraceCommand(){}

	public static void main( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] rngtrace needs a replay file" );
			System.exit( 1 );
			return;
		}

		File file = CliOptions.resolve( new File( args[ 0 ] ) );
		CliOptions options = CliOptions.parse( Cli.tail( args ) );
		Cli.boot( options );

		java.nio.file.Path out = options.rngTraceOut;
		java.nio.file.Path golden = options.rngTraceGolden;

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read the replay " + file
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		if ( replay.seedText == null || replay.seedText.trim().isEmpty() ){
			System.err.println( "[ERROR] " + file.getName() + " records no seed, so its world cannot be "
					+ "rebuilt and there is nothing to trace." );
			System.exit( 1 );
			return;
		}

		EnvConfig config = ReplayIO.configFor( replay );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );

		RngTrace trace = new RngTrace();
		com.watabou.utils.RandomTrace.enable();
		com.watabou.utils.RandomTrace.attribute();

		ReplayIO.verify( replay, env, trace.observer() );

		if (options.rngTraceSites != null){
			try {
				java.nio.file.Files.write( options.rngTraceSites,
						String.join( "\n", com.watabou.utils.RandomTrace.siteReport() )
								.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
			} catch (IOException e){
				System.err.println( "[ERROR] cannot write the draw-site tally to "
						+ options.rngTraceSites + " (" + e.getClass().getSimpleName() + ")" );
				System.exit( 1 );
				return;
			}
			System.out.println( "wrote " + com.watabou.utils.RandomTrace.sites().size()
					+ " draw sites to " + options.rngTraceSites );
		}

		com.watabou.utils.RandomTrace.attribute( false );
		com.watabou.utils.RandomTrace.disable();

		if (out != null){
			try {
				trace.writeTo( out );
			} catch (IOException e){
				System.err.println( "[ERROR] cannot write the rng trace to " + out
						+ " (" + e.getClass().getSimpleName() + ")" );
				System.exit( 1 );
				return;
			}
			System.out.println( "wrote " + trace.size() + " trace samples to " + out );
		}

		if (golden != null){
			String problem = RngTrace.compare( golden, trace );
			if (problem.isEmpty()){
				System.out.println( "[OK]     rng trace matches " + golden );
			} else {
				System.err.println( "[ERROR] rng trace differs from " + golden );
				System.err.println( "        " + problem.replace( "\n", "\n        " ) );
				System.exit( 1 );
				return;
			}
		}

		if (out == null && golden == null){
			System.out.println( trace.render() );
		}
	}
}