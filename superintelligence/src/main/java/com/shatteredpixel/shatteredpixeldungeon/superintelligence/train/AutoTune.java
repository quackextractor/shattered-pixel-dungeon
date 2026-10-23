package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ParallelFixtures;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class AutoTune {

	static final long JVM_OVERHEAD_MB = 300;
	static final long OS_RESERVE_MB = 1024;
	static final double THREAD_TOLERANCE = 1.05;
	static final int PROBE_MINIBATCHES = 4;

	public final int workers;
	public final int updateThreads;
	public final String report;

	private AutoTune( int workers, int updateThreads, String report ){
		this.workers = workers;
		this.updateThreads = updateThreads;
		this.report = report;
	}

	public static AutoTune measure( EnvConfig config, PpoHyperparameters hyper, int minibatchSize ){
		int cores = ResourceStats.availableProcessors();
		long availableMb = availableMemoryBytes() / (1024L * 1024);
		long trainerMb = Runtime.getRuntime().maxMemory() / (1024L * 1024) + JVM_OVERHEAD_MB;
		long workerMb = megabytes( hyper.workerHeapMax ) + JVM_OVERHEAD_MB;
		int workers = workersFor( cores, availableMb, trainerMb, workerMb );

		List< Integer > counts = threadCounts( Math.min( cores, minibatchSize ) );
		double[] msPerSample = new double[ counts.size() ];
		for (int i = 0; i < counts.size(); i++){
			msPerSample[ i ] = timeUpdate( config, counts.get( i ), minibatchSize, hyper.chunkLength );
		}
		int threads = counts.get( pickThreads( msPerSample ) );

		StringBuilder sb = new StringBuilder();
		sb.append( String.format( Locale.ROOT, "autotune %d logical processors, %d MB available,"
				+ " %d MB per worker, %d MB trainer -> %d workers%n", cores, availableMb, workerMb,
				trainerMb, workers ) );
		for (int i = 0; i < counts.size(); i++){
			sb.append( String.format( Locale.ROOT, "autotune update %2d threads %.2f ms/sample%n",
					counts.get( i ), msPerSample[ i ] ) );
		}
		sb.append( "autotune -> --workers " ).append( workers )
				.append( " --update-threads " ).append( threads );
		return new AutoTune( workers, threads, sb.toString() );
	}

	public static AutoTune fixed( int workers, int updateThreads ){
		return new AutoTune( workers, updateThreads, "" );
	}

	public static void main( String[] args ){
		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder.Loaded loaded =
					com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder.load( null );
			System.out.println( measure( loaded.config, loaded.hyper, loaded.hyper.minibatchSize ).report );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read the configuration: " + e.getMessage() );
			System.exit( 1 );
		}
	}

	public static int workersFor( int cores, long availableMb, long trainerMb, long workerMb ){
		long byMemory = (availableMb - OS_RESERVE_MB - trainerMb) / Math.max( 1, workerMb );
		return (int) Math.max( 1, Math.min( cores, byMemory ) );
	}

	public static int pickThreads( double[] msPerSample ){
		double best = Double.MAX_VALUE;
		for (double ms : msPerSample) best = Math.min( best, ms );
		for (int i = 0; i < msPerSample.length; i++){
			if (msPerSample[ i ] <= best * THREAD_TOLERANCE) return i;
		}
		return msPerSample.length - 1;
	}

	public static List< Integer > threadCounts( int limit ){
		List< Integer > out = new ArrayList<>();
		for (int t = 1; t < limit; t *= 2) out.add( t );
		out.add( Math.max( 1, limit ) );
		return out;
	}

	public static int probeSamples( int minibatchSize, int chunkLength ){
		return PROBE_MINIBATCHES * minibatchSize * chunkLength;
	}

	public static long megabytes( String heap ){
		String h = heap.trim().toLowerCase( Locale.ROOT );
		long n = Long.parseLong( h.replaceAll( "[^0-9]", "" ) );
		if (h.endsWith( "g" )) return n * 1024;
		if (h.endsWith( "k" )) return n / 1024;
		if (h.endsWith( "m" )) return n;
		return n / (1024L * 1024);
	}

	public static long availableMemoryBytes(){
		try {
			Path meminfo = Path.of( "/proc/meminfo" );
			if (Files.isReadable( meminfo )){
				long bytes = memAvailable( Files.readAllLines( meminfo ) );
				if (bytes > 0) return bytes;
			}
		} catch (IOException | RuntimeException ignored){
		}
		return TrainOptions.availableMemoryBytes();
	}

	public static long memAvailable( List< String > meminfo ){
		for (String line : meminfo){
			if (!line.startsWith( "MemAvailable:" )) continue;
			String[] parts = line.trim().split( "\\s+" );
			return Long.parseLong( parts[ 1 ] ) * 1024;
		}
		return -1;
	}

	private static double timeUpdate( EnvConfig config, int threads, int minibatchSize, int chunkLength ){
		PPO ppo = new PPO( config, new Random( 12345L ) );
		ppo.threads = threads;
		ppo.epochs = 1;
		ppo.minibatchSize = minibatchSize;
		ppo.chunkLength = chunkLength;
		try {
			double best = Double.MAX_VALUE;
			for (int r = 0; r < 2; r++){
				List< Transition > samples = ParallelFixtures.batch( ppo.network, config,
						probeSamples( minibatchSize, chunkLength ), 4242L );
				for (int i = 0; i < samples.size(); i++){
					samples.get( i ).episodeId = i / ppo.chunkLength;
					samples.get( i ).stepIndex = i % ppo.chunkLength;
				}
				ppo.addAll( samples );
				long start = System.nanoTime();
				ppo.update();
				best = Math.min( best, (System.nanoTime() - start) / 1e6 / samples.size() );
			}
			return best;
		} finally {
			ppo.close();
		}
	}
}
