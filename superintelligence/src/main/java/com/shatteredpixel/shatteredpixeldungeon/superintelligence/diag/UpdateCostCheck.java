package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.Locale;
import java.util.Random;

public class UpdateCostCheck {

	private static final int WARMUP = 20;
	private static final int DEFAULT_SAMPLES = 64;

	private static final int WORKERS = 20;
	private static final int EPISODES_PER_WORKER = 16;
	private static final int MEAN_TURNS = 150;
	private static final double WEIGHT_PUSH_MB = 14.3;

	public static void main( String[] args ){
		int samples = DEFAULT_SAMPLES;
		for (int i = 0; i < args.length - 1; i++){
			if (args[ i ].equals( "--samples" )) samples = Integer.parseInt( args[ ++i ] );
		}

		EnvConfig config = new EnvConfig();
		int gridSize = config.spatialChannels() * config.gridWidth * config.gridHeight;

		Network network = new Network( config, new Random( 12345L ) );
		Transition[] batch = syntheticBatch( config, gridSize, samples, 4242L );

		float[] grid = new float[ gridSize ];
		float[] headGrad = new float[ Action.size() ];

		for (int i = 0; i < WARMUP; i++){
			oneStep( network, batch[ i % batch.length ], grid, headGrad, true );
		}

		Report forward = time( network, batch, grid, headGrad, false, false );
		Report full = time( network, batch, grid, headGrad, true, false );
		Report clipped = time( network, batch, grid, headGrad, true, true );

		print( forward, full, clipped, network.parameterCount() );
	}

	private static void oneStep( Network network, Transition t, float[] grid, float[] headGrad,
			boolean backward ){

		t.unpackGrid( grid );
		network.forward( grid, t.inventory, t.hero );

		if (!backward) return;

		java.util.Arrays.fill( headGrad, 0f );
		headGrad[ 0 ] = 1f;
		network.backward( headGrad, null, null, 1f );
	}

	private static Report time( Network network, Transition[] batch, float[] grid,
			float[] headGrad, boolean backward, boolean optimiser ){

		int reps = 3;
		double best = Double.MAX_VALUE;

		for (int r = 0; r < reps; r++){
			long start = System.nanoTime();
			for (int i = 0; i < batch.length; i++){
				oneStep( network, batch[ i ], grid, headGrad, backward );
			}
			if (optimiser){
				network.scaleGradients( 1f / batch.length );
				network.clipGradients( network.gradClip );
				network.step( 1f );
			}
			best = Math.min( best, (System.nanoTime() - start) / 1e6 );
		}

		return new Report( best / batch.length );
	}

	private static Transition[] syntheticBatch( EnvConfig config, int gridSize, int count, long seed ){
		Random rng = new Random( seed );

		float[] gridSrc = new float[ gridSize ];
		Transition[] batch = new Transition[ count ];

		for (int i = 0; i < count; i++){
			for (int g = 0; g < gridSize; g++) gridSrc[ g ] = rng.nextFloat() > 0.5f ? 1f : 0f;

			Transition t = Transition.take( gridSize,
					config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT,
					HeroEncoder.FEATURES,
					Action.size(), config.maxSlots, ActionMapper.TARGET_COUNT );

			t.packGrid( gridSrc );
			for (int k = 0; k < t.inventory.length; k++) t.inventory[ k ] = rng.nextFloat();
			for (int k = 0; k < t.hero.length; k++) t.hero[ k ] = rng.nextFloat();
			java.util.Arrays.fill( t.actionMask, 1f );
			java.util.Arrays.fill( t.slotMask, 1f );
			java.util.Arrays.fill( t.targetMask, 1f );

			t.actionIndex = rng.nextInt( Action.size() );
			t.slotIndex = rng.nextInt( config.maxSlots );
			t.oldLogProbability = -1.5f;
			t.advantage = 0.8f;
			t.returnValue = 1.2f;
			t.value = 0.4f;

			batch[ i ] = t;
		}

		return batch;
	}

	private static void print( Report forward, Report full, Report clipped, long parameters ){
		System.out.println();
		System.out.println( Ansi.wrap( "update cost", Ansi.BOLD + Ansi.CYAN ) + "  "
				+ String.format( Locale.US, "%,d", parameters ) + " parameters, single thread" );
		System.out.println();
		System.out.println( "  forward only            " + ms( forward.perSampleMs ) + " ms/sample" );
		System.out.println( "  forward + backward      " + ms( full.perSampleMs ) + " ms/sample" );
		System.out.println( "  + average, clip, adam   " + ms( clipped.perSampleMs ) + " ms/sample"
				+ "   (per-sample cost of one minibatch, optimiser amortised)" );

		double collected = (double) WORKERS * EPISODES_PER_WORKER * MEAN_TURNS;
		System.out.println();
		System.out.println( "  projected per generation at " + WORKERS + " workers x "
				+ EPISODES_PER_WORKER + " episodes x " + MEAN_TURNS + " turns ("
				+ String.format( Locale.US, "%,.0f", collected ) + " steps collected):" );
		System.out.println();
		System.out.printf( Locale.US, "    %-12s %14s %14s %14s%n",
				"sample rate", "sampled steps", "update 4 ep", "update 1 ep" );
		System.out.printf( Locale.US, "    %-12s %14s %14s %14s%n",
				"------------", "--------------", "-----------", "-----------" );

		for (double rate : new double[] { 0.01, 0.02, 0.05, 0.10, 0.25, 1.00 }){
			double steps = collected * rate;
			System.out.printf( Locale.US, "    %-12s %14s %13s %14s%n",
					String.format( Locale.US, "%.0f%%", rate * 100 ),
					String.format( Locale.US, "%,.0f", steps ),
					seconds( steps * 4, clipped.perSampleMs ),
					seconds( steps, clipped.perSampleMs ) );
		}

		System.out.println();
		System.out.println( "  weight push per generation " + ms( (double) WORKERS * WEIGHT_PUSH_MB / 800 )
				+ "s of pipe at 800 MB/s, on the same thread as the update" );
		System.out.println();
	}

	private static String seconds( double samples, double msPerSample ){
		double s = samples * msPerSample / 1000;
		if (s > 600) return String.format( Locale.US, "%.2f h", s / 3600 );
		return String.format( Locale.US, "%.1f s", s );
	}

	private static String ms( double value ){
		return String.format( Locale.US, "%.2f", value );
	}

	private static class Report {
		final double perSampleMs;

		Report( double perSampleMs ){
			this.perSampleMs = perSampleMs;
		}
	}
}