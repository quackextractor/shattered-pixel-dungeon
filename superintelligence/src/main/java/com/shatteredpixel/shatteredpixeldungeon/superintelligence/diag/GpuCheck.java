package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.List;
import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class GpuCheck {

	private static final int CHECKS = 4;

	private static final double TOLERANCE = 1e-4;

	private static final double STEP_TOLERANCE = 1e-2;

	private static final long SEED = 20261029L;

	private static final String[] METRICS = { "policy loss", "value loss", "entropy", "KL", "clip fraction" };

	public static void main( String[] args ){
		PPO probe = new PPO( small(), new Random( SEED ));
		String device = probe.useDevice( "auto" );
		probe.close();
		if (device.startsWith( "cpu" )){
			System.out.println( "[WARN]   gpu update: " + device + ", " + CHECKS + " checks skipped" );
			System.exit( 0 );
		}
		System.out.println( "  device " + device );

		checkTheGradientMatchesTheCpu( "10x10", small(), 1, 128 );
		checkTheGradientMatchesTheCpu( "10x10", small(), 4, 128 );
		checkTheGradientMatchesTheCpu( "48x48", new EnvConfig(), 4, 128 );
		checkTheShippedSizeIsFaster();

		System.exit( GateReport.report( "gpu update", CHECKS ));
	}

	private static void checkTheGradientMatchesTheCpu( String label, EnvConfig config, int threads, int samples ){
		Run cpu = update( config, "cpu", threads, samples, 1 );
		Run gpu = update( config, "gpu", threads, samples, 1 );

		double norm = 0, worst = 0;
		int at = -1, tensor = -1;
		for (float[] g : cpu.gradients) for (float v : g) norm += (double) v * v;
		norm = Math.max( 1e-12, Math.sqrt( norm ));
		for (int i = 0; i < cpu.gradients.length; i++){
			for (int k = 0; k < cpu.gradients[ i ].length; k++){
				double d = Math.abs( cpu.gradients[ i ][ k ] - gpu.gradients[ i ][ k ] ) / norm;
				if (d > worst){
					worst = d;
					tensor = i;
					at = k;
				}
			}
		}
		String where = label + " at " + threads + " threads";
		if (norm <= 1e-9){
			fail( where + ": the CPU gradient is zero, so the comparison proves nothing" );
			return;
		}
		if (worst > TOLERANCE){
			fail( where + ": the GPU gradient differs from the CPU one by " + worst + " of its norm at tensor "
					+ tensor + " element " + at + ", tolerance " + TOLERANCE );
			return;
		}
		double step = 0, moved = 0;
		for (int i = 0; i < cpu.layers.length; i++){
			for (int part = 0; part < 2; part++){
				float[] b = part == 0 ? cpu.before[ i ].weights : cpu.before[ i ].bias;
				float[] x = part == 0 ? cpu.layers[ i ].weights : cpu.layers[ i ].bias;
				float[] y = part == 0 ? gpu.layers[ i ].weights : gpu.layers[ i ].bias;
				for (int k = 0; k < b.length; k++){
					step += (double) ( x[ k ] - b[ k ] ) * ( x[ k ] - b[ k ] );
					moved += (double) ( x[ k ] - y[ k ] ) * ( x[ k ] - y[ k ] );
				}
			}
		}
		double drift = Math.sqrt( moved / Math.max( 1e-30, step ));
		if (step == 0 || drift > STEP_TOLERANCE){
			fail( where + ": the GPU Adam step moved the parameters " + drift + " of the CPU step's length away from it,"
					+ " tolerance " + STEP_TOLERANCE );
			return;
		}
		for (int m = 0; m < METRICS.length; m++){
			if (Math.abs( cpu.metrics[ m ] - gpu.metrics[ m ] ) > 1e-3 * Math.max( 1f, Math.abs( cpu.metrics[ m ] ))){
				fail( where + ": " + METRICS[ m ] + " reads " + gpu.metrics[ m ] + " on the GPU and "
						+ cpu.metrics[ m ] + " on the CPU" );
				return;
			}
		}
		System.out.println( "  " + where + ": GPU and CPU gradients agree to " + worst + " of the norm "
				+ (float) norm + ", parameters after the Adam step to " + (float) drift + " of the step; policy loss "
				+ gpu.metrics[ 0 ] + " on both" );
	}

	private static void checkTheShippedSizeIsFaster(){
		EnvConfig config = new EnvConfig();
		update( config, "cpu", 8, 128, 1 );
		update( config, "gpu", 8, 128, 1 );
		Run cpu = update( config, "cpu", 8, 1024, 1 );
		Run gpu = update( config, "gpu", 8, 1024, 1 );
		String line = "48x48, 1024 samples, minibatch 8 x 16 steps, 8 threads: cpu " + format( cpu.msPerSample )
				+ " ms/sample, gpu " + format( gpu.msPerSample ) + " ms/sample";
		if (gpu.msPerSample >= cpu.msPerSample){
			fail( line + "; the GPU update is not faster than the CPU one" );
			return;
		}
		System.out.println( "  " + line + " (" + format( cpu.msPerSample / gpu.msPerSample ) + "x)" );
	}

	private static class Run {
		Network.Layer[] before, layers;
		float[][] gradients;
		float[] metrics;
		double msPerSample;
	}

	private static Run update( EnvConfig config, String device, int threads, int samples, int epochs ){
		PPO ppo = new PPO( config, new Random( SEED ));
		try {
			ppo.useDevice( device );
			ppo.threads = threads;
			ppo.epochs = epochs;
			ppo.chunkLength = 16;
			ppo.minibatchSize = samples <= 128 ? samples / 16 : 8;
			List< Transition > batch = ParallelFixtures.batch( ppo.network, config, samples, 4242L );
			for (int i = 0; i < batch.size(); i++){
				batch.get( i ).episodeId = i / 64;
				batch.get( i ).stepIndex = i;
			}
			ppo.buffer().addAll( batch );
			Network.Layer[] before = copy( ppo.network.layers() );
			long start = System.nanoTime();
			ppo.update();
			Run r = new Run();
			r.before = before;
			r.layers = copy( ppo.network.layers() );
			r.msPerSample = ( System.nanoTime() - start ) / 1e6 / samples / epochs;
			float[][] g = ppo.network.gradientArrays();
			r.gradients = new float[ g.length ][];
			for (int i = 0; i < g.length; i++) r.gradients[ i ] = g[ i ].clone();
			r.metrics = new float[]{ ppo.lastPolicyLoss, ppo.lastValueLoss, ppo.lastEntropy,
					ppo.lastKLDivergence, ppo.lastClipFraction };
			return r;
		} finally {
			ppo.close();
		}
	}

	private static Network.Layer[] copy( Network.Layer[] layers ){
		Network.Layer[] out = new Network.Layer[ layers.length ];
		for (int i = 0; i < layers.length; i++){
			Network.Layer l = layers[ i ];
			out[ i ] = new Network.Layer( l.name, l.in, l.out, l.weights.clone(), l.bias.clone() );
		}
		return out;
	}

	private static String format( double v ){
		return String.format( java.util.Locale.ROOT, "%.3f", v );
	}

	private static EnvConfig small(){
		EnvConfig config = new EnvConfig();
		config.gridWidth = 10;
		config.gridHeight = 10;
		config.maxSlots = 4;
		return config;
	}
}
