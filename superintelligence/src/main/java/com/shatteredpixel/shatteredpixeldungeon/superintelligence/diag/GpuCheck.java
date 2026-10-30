package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.BatchedInference;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.RemoteNetwork;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.List;
import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class GpuCheck {

	private static final int CHECKS = 6;

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
		checkTheGradientMatchesTheCpu( "48x48 with teacher and demo rows, short chunks, 2 epochs of 4 minibatches", new EnvConfig(), 4, -512 );
		checkTheShippedSizeIsFaster();
		checkBatchedInferenceMatchesTheCpu();

		System.exit( GateReport.report( "gpu update", CHECKS ));
	}

	private static void checkTheGradientMatchesTheCpu( String label, EnvConfig config, int threads, int samples ){
		boolean rich = samples < 0;
		int epochs = rich ? 2 : 1;
		Run cpu = update( config, "cpu", threads, Math.abs( samples ), epochs, rich );
		Run gpu = update( config, "gpu", threads, Math.abs( samples ), epochs, rich );

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
			System.out.println( "[WARN]   " + line + "; this GPU is not faster than the CPU update" );
			return;
		}
		System.out.println( "  " + line + " (" + format( cpu.msPerSample / gpu.msPerSample ) + "x)" );
	}

	private static void checkBatchedInferenceMatchesTheCpu(){
		EnvConfig config = new EnvConfig();
		int envs = 3, steps = 6;
		Network source = new Network( config, new Random( SEED ));
		BatchedInference server = new BatchedInference( source );
		try {
			Network[] cpu = new Network[ envs ];
			RemoteNetwork[] gpu = new RemoteNetwork[ envs ];
			for (int e = 0; e < envs; e++){
				cpu[ e ] = new Network( config, new Random( 1 ));
				cpu[ e ].copyParametersFrom( source );
				gpu[ e ] = new RemoteNetwork( config, server, -1 );
			}
			double[] worst = new double[ envs ];
			Thread[] threads = new Thread[ envs ];
			for (int e = 0; e < envs; e++){
				final int id = e;
				threads[ e ] = new Thread( () -> {
					Random rng = new Random( 100 + id );
					float[] grid = new float[ cpu[ id ].gridLength() ];
					float[] inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
					float[] hero = new float[ HeroEncoder.FEATURES ];
					for (int t = 0; t < steps; t++){
						java.util.Arrays.fill( grid, 0f );
						for (int i = 0; i < grid.length; i++) if (rng.nextFloat() < 0.02f) grid[ i ] = 1f;
						for (int i = 0; i < hero.length; i++) hero[ i ] = rng.nextFloat();
						for (int i = 0; i < inventory.length; i++) inventory[ i ] = rng.nextFloat() < 0.3f ? rng.nextFloat() : 0f;
						cpu[ id ].forward( grid, inventory, hero );
						gpu[ id ].forward( grid, inventory, hero );
						float[][] a = { cpu[ id ].actionLogits(), cpu[ id ].slotLogits(), cpu[ id ].targetLogits(), { cpu[ id ].value() } };
						float[][] b = { gpu[ id ].actionLogits(), gpu[ id ].slotLogits(), gpu[ id ].targetLogits(), { gpu[ id ].value() } };
						for (int h = 0; h < a.length; h++){
							for (int k = 0; k < a[ h ].length; k++){
								worst[ id ] = Math.max( worst[ id ], Math.abs( a[ h ][ k ] - b[ h ][ k ] ) / Math.max( 1f, Math.abs( a[ h ][ k ] )));
							}
						}
					}
				} );
				threads[ e ].start();
			}
			for (Thread t : threads) t.join();
			new RemoteNetwork( config, server, gpu[ 0 ].slot() );
			if (server.slots() != envs){
				fail( "a worker rebuilding its network on a policy push opened another inference slot: "
						+ server.slots() + " slots for " + envs + " envs, so batches can never fill" );
				return;
			}
			double max = 0;
			for (double w : worst) max = Math.max( max, w );
			if (max > 1e-4){
				fail( "batched GPU inference for " + envs + " envs over " + steps + " steps differs from the CPU network by " + max );
				return;
			}
			System.out.println( "  batched GPU inference, " + envs + " envs x " + steps + " steps on their own threads: logits and value"
					+ " agree with the CPU network to " + (float) max );
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			fail( "interrupted while comparing batched inference" );
		} finally {
			server.close();
		}
	}

	private static class Run {
		Network.Layer[] before, layers;
		float[][] gradients;
		float[] metrics;
		double msPerSample;
	}

	private static Run update( EnvConfig config, String device, int threads, int samples, int epochs ){
		return update( config, device, threads, samples, epochs, false );
	}

	private static Run update( EnvConfig config, String device, int threads, int samples, int epochs, boolean rich ){
		PPO ppo = new PPO( config, new Random( SEED ));
		try {
			ppo.useDevice( device );
			ppo.threads = threads;
			ppo.epochs = epochs;
			ppo.chunkLength = 16;
			ppo.minibatchSize = samples <= 128 ? samples / 16 : 8;
			List< Transition > batch = ParallelFixtures.batch( ppo.network, config, samples, 4242L );
			for (int i = 0; i < batch.size(); i++){
				Transition t = batch.get( i );
				t.episodeId = rich ? i / 11 : i / 64;
				t.stepIndex = i;
				if (rich){
					t.teacherIndex = i % 5 - 1;
					t.demonstration = i % 7 == 0;
					t.actionMask[ 0 ] = 0f;
				}
			}
			if (rich){
				ppo.minibatchSize = 13;
				ppo.teacherWeight = 0.3f;
				ppo.demoWeight = 0.5f;
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
