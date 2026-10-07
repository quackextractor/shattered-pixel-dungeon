package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.File;

/**
 * Parsed command line for the trainer.
 *
 * A top-level class rather than a nested one because it is a value with defaults and reasons, not a
 * part of the training loop. Each default that is not "obvious" carries its reasoning here, so the
 * numbers can be argued with rather than discovered.
 */
public class TrainOptions {

	/**
	 * Defaults to one worker per logical processor.
	 *
	 * Each worker simulates on a single thread, so leaving a core unassigned is leaving throughput on
	 * the floor - and a previous fixed default of 4 left 16 of 20 cores idle on a 20-thread machine.
	 * Capped by {@link #defaultWorkerCount()} so a large pool cannot page: the machine's pagefile is
	 * only 2GB, and thrashing is far slower than leaving a core unused.
	 */
	public int workers = 0;

	/**
	 * Episodes each worker runs per generation.
	 *
	 * Distinct from the pool size on purpose. A generation ends with an update and a policy push, and
	 * no worker can run during either - the push alone moves ~14MB per worker. With the two numbers
	 * tied together that barrier was 45% of wall time at four workers. More episodes per worker
	 * amortises it, at the cost of coarser policy updates.
	 */
	public int episodes = 0;

	public int generations = 100;
	public long seed = 12345L;

	/**
	 * Epochs per update.
	 *
	 * The dominant term in an update is forward and backward, so this scales it linearly - measured
	 * at 11.29 ms per sample, which is ~107 s of trainer CPU per generation at 4 epochs and a 5% sample
	 * rate, and ~53 s at 2. Two is the default because four was never a considered choice: it gives 300
	 * Adam steps over a 2,400-sample batch, which is far more optimiser movement than a batch that size
	 * supports. See `PLAN-data-flow.md`.
	 */
	public int epochs = 2;

	/** Samples per minibatch, which sets the gradient reduction size in a parallel update. */
	public int minibatchSize = 32;

	/**
	 * Fraction of an episode's transitions whose observation reaches the trainer.
	 *
	 * A knob rather than a constant because the cost of an update is linear in it: at 5% and 2 epochs
	 * the update is ~53 s of trainer CPU per generation, and that is the figure a sample-rate sweep
	 * has to move. Clamped to [0,1] in {@link Trainer}; 0 is allowed because the 20-step tail is
	 * retained regardless, which is how "off" is expressed rather than being a special case.
	 */
	public double sampleRate = 0.05;

	/** Ceiling on one episode's retained observations, ~98 MB at 2048. */
	public int maxSampledPerEpisode = 2048;

	/** Ceiling on one generation's transitions in the update buffer, ~397 MB at 48.5 KB a step. */
	public int maxSamplesPerGeneration = 8192;

	public String javaHome = "";
	public String classpath = System.getProperty( "java.class.path" );
	public File workDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-train" );
	public long stallSeconds = 180;

	/**
	 * Per-generation metrics, one CSV row each. {@code null} disables it.
	 *
	 * Defaults under the work directory rather than the working directory, because the work directory
	 * is where a run's replays already go and is therefore the place a run's output can be found
	 * without knowing where the trainer was launched from.
	 */
	public File metricsCsv;

	public TrainOptions(){
		metricsCsv = new File( workDir, "metrics.csv" );
	}

	public static TrainOptions parse( String[] args ){
		TrainOptions o = new TrainOptions();
		for (int i = 0; i < args.length; i++){
			switch (args[ i ]) {
				case "--workers":     o.workers = Integer.parseInt( args[ ++i ] ); break;
				case "--generations": o.generations = Integer.parseInt( args[ ++i ] ); break;
				case "--episodes":   o.episodes = Integer.parseInt( args[ ++i ] ); break;
				case "--seed":        o.seed = Long.parseLong( args[ ++i ] ); break;
				case "--java-home":   o.javaHome = args[ ++i ]; break;
				case "--classpath":   o.classpath = args[ ++i ]; break;
				case "--out":         o.workDir = new File( args[ ++i ] ); break;
				case "--stall-seconds":
					//how long a worker may be silent before the run is failed as a protocol desync.
					//A blocked pipe costs no CPU, so without this a stall is indistinguishable from a
					//long episode.
					o.stallSeconds = Long.parseLong( args[ ++i ] ); break;
				case "--epochs":      o.epochs = Integer.parseInt( args[ ++i ] ); break;
				case "--minibatch":   o.minibatchSize = Integer.parseInt( args[ ++i ] ); break;
				//these three existed as public Trainer fields with these defaults, but were never
				//reachable from the command line - so the knobs the plan documents could not be turned
				case "--sample-rate": o.sampleRate = Double.parseDouble( args[ ++i ] ); break;
				case "--max-sampled-per-episode":
					o.maxSampledPerEpisode = Integer.parseInt( args[ ++i ] ); break;
				case "--max-samples":
					o.maxSamplesPerGeneration = Integer.parseInt( args[ ++i ] ); break;
				case "--metrics":     o.metricsCsv = new File( args[ ++i ] ); break;
				default:
					if (args[ i ].startsWith( "--" )){
						System.err.println( "[WARN] unknown option: " + args[ i ] );
					}
			}
		}
		o.workers = o.workers > 0 ? o.workers : defaultWorkerCount();
		o.episodes = o.episodes > 0 ? o.episodes : o.workers;
		if (o.epochs < 1) o.epochs = 1;
		if (o.minibatchSize < 1) o.minibatchSize = 1;
		return o;
	}

	/**
	 * One worker per logical processor, trimmed to what memory allows.
	 *
	 * A worker holds at most a capped set of retained observations (~98MB at 2048) plus its own engine
	 * overhead, so ~1.2GB per worker is a safe budget with room for the trainer itself.
	 */
	static int defaultWorkerCount(){
		int cores = ResourceStats.availableProcessors();
		long budget = System.getProperty( "os.name" ) == null
				? Long.MAX_VALUE
				: availableMemoryBytes() / (1200L * 1024 * 1024);
		return (int) Math.max( 1, Math.min( cores, budget ) );
	}

	/** Usable physical memory, or {@link Long#MAX_VALUE} if the JVM will not say. */
	static long availableMemoryBytes(){
		try {
			java.lang.management.OperatingSystemMXBean os =
					java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			java.lang.reflect.Method m = os.getClass().getMethod( "getFreeMemorySize" );
			Object v = m.invoke( os );
			if (v instanceof Number) return ((Number) v).longValue();
		} catch (ReflectiveOperationException | RuntimeException ignored){
			//not available on every JVM; fall through to "unlimited"
		}
		return Long.MAX_VALUE;
	}
}