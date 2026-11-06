package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;

import java.io.File;
import java.io.IOException;
import java.util.Random;

public final class EvalCommand {

	private static final HeroClass[] HEROES = HeroClass.values();

	private EvalCommand(){}

	public static void main( String[] args ){
		File weights = CliOptions.resolve( new File( args[ 0 ] ) );
		int episodes = Integer.parseInt( args[ 1 ] );
		String[] temperatures = args[ 2 ].split( "," );
		CliOptions options = CliOptions.parse( java.util.Arrays.copyOfRange( args, 3, args.length ));
		Cli.boot( options );
		EnvConfig config = Cli.configFor( options );
		Network network = new Network( config, new Random( 1L ));
		try {
			Checkpoint.load( weights, network, config );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read " + weights + ": " + e.getMessage() );
			System.exit( 1 );
			return;
		}
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		for (String temperature : temperatures){
			evaluate( env, config, network, episodes, Float.parseFloat( temperature ));
		}
	}

	private static void printRow( long[][] confusion, int teacher ){
		long total = 0;
		for (long count : confusion[ teacher ]) total += count;
		if (total == 0) return;
		StringBuilder row = new StringBuilder( String.format( "  teacher %-16s %8d:", Action.fromIndex( teacher ), total ));
		for (int taken = 0; taken < confusion.length; taken++){
			if (confusion[ teacher ][ taken ] * 20 >= total){
				row.append( String.format( " %s %.0f%%", Action.fromIndex( taken ), 100.0 * confusion[ teacher ][ taken ] / total ));
			}
		}
		System.out.println( row );
	}

	private static void evaluate( SPDEnv env, EnvConfig config, Network network, int episodes, float temperature ){
		EpisodeCollector collector = new EpisodeCollector( config, network, new Random( 2L ), new Random( 3L ), 0.99f, 0.95f, 0f );
		collector.teacher( new DescentBot() );
		collector.temperature( temperature );
		long[][] confusion = new long[ Action.size() ][ Action.size() ];
		collector.confusion( confusion );
		long start = System.nanoTime();
		int deepest = 0, floor4 = 0, floor6 = 0, turns = 0;
		for (int e = 0; e < episodes; e++){
			collector.run( env, "EVAL-" + e, HEROES[ e % HEROES.length ], e );
			int depth = Statistics.deepestFloor;
			deepest += depth;
			if (depth >= 4) floor4++;
			if (depth >= 6) floor6++;
			turns += env.turnsTotal();
		}
		System.out.printf( "[OK]     temperature %.2f: depth %.3f, floor 4+ %d, floor 6+ %d, turns %d, teacher agreement %.3f, %d episodes in %.0f s%n",
				temperature, deepest / (double) episodes, floor4, floor6, turns / episodes, collector.teacherAgreement(), episodes,
				( System.nanoTime() - start ) / 1e9 );
		for (int t = 0; t < confusion.length; t++){
			printRow( confusion, t );
		}
	}
}
