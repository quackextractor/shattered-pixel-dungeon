package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;

import java.io.File;
import java.io.IOException;
import java.util.Random;

public class WeightsDiff {

	public static void main( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] weightsdiff needs a checkpoint file" );
			System.exit( 1 );
			return;
		}

		File file = new File( args[ 0 ] );
		EnvConfig config = new EnvConfig();

		try {

			Network fresh = new Network( config, new Random( 1L ) );
			Network resumed = new Network( config, new Random( 1L ) );

			Checkpoint.Meta meta = Checkpoint.load( file, resumed, config );

			Stats stats = new Stats();
			Network.Layer[] a = fresh.layers();
			Network.Layer[] b = resumed.layers();
			for (int i = 0; i < a.length; i++){
				compare( a[ i ].weights, b[ i ].weights, a[ i ].name, stats );
				compare( a[ i ].bias, b[ i ].bias, a[ i ].name + ".b", stats );
			}

			System.out.println( "checkpoint   " + file.getPath() );
			System.out.println( "header       " + meta );
			System.out.println( "fresh adam   " + fresh.adamSteps() + " steps" );
			System.out.println( "resumed adam " + resumed.adamSteps() + " steps" );
			System.out.println( "weights      " + stats.count + " compared" );
			System.out.println( "mean |diff|  " + String.format( java.util.Locale.ROOT, "%.6f", stats.sum / Math.max( 1, stats.count ) ) );
			System.out.println( "max  |diff|  " + String.format( java.util.Locale.ROOT, "%.6f", stats.largest )
					+ "  in " + stats.worst );
			System.out.println( "changed      " + String.format( java.util.Locale.ROOT, "%.2f", 100.0 * stats.changed
					/ Math.max( 1, stats.count ) ) + "% of weights differ from initialisation" );

			if (stats.largest == 0){
				System.out.println();
				System.out.println( "  identical to a fresh initialisation. Expected only if this"
						+ " checkpoint was written before any update ran." );
			}
		} catch (IOException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
		}
	}

	private static class Stats {
		double sum;
		double largest;
		String worst = "";
		long count;
		long changed;
	}

	private static void compare( float[] fresh, float[] resumed, String name, Stats s ){
		for (int i = 0; i < fresh.length; i++){
			double d = Math.abs( fresh[ i ] - resumed[ i ] );
			s.sum += d;
			s.count++;
			if (d != 0) s.changed++;
			if (d > s.largest){
				s.largest = d;
				s.worst = name;
			}
		}
	}
}
