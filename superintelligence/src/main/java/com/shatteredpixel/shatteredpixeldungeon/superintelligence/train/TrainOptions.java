package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.File;
import java.io.IOException;

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
	 * Threads for the update. Zero or one is the serial path, exactly.
	 *
	 * <p>Defaulted to one rather than to a physical core count. The update is the trainer's critical
	 * path, so it wants every core that is not running a worker - but the same run already uses
	 * {@code --workers} processes, and defaulting both to "all the cores" would oversubscribe the
	 * machine by 20x. Setting this is therefore a decision about how to divide a fixed budget between
	 * collection and the update, which is a judgement call, not a default.
	 */
	public int updateThreads = 1;

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

	/**
	 * External settings file, or null for the compiled defaults.
	 *
	 * <p>Applied before the flags below, which then override it: a value somebody typed on the command
	 * line beats a value from a file, or the file becomes a trap.
	 */
	public java.nio.file.Path configFile;
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

	/**
	 * Where the policy is written, or {@code null} to write nothing.
	 *
	 * Defaulted rather than required, because a smoke test should not leave 43 MB behind and
	 * checkpointing was once described as plumbing. It was plumbing; the thing it enables — a run that
	 * can be stopped and continued — is what {@code research.md:40} asks for, and that is not.
	 */
	public File save;

	/**
	 * Generations between checkpoints, and the last generation always writes.
	 *
	 * 25 rather than every generation because the file is ~43 MB (parameters plus four Adam moments per
	 * tensor) and rewriting it 100 times is 4.3 GB of writes for a policy that changes little between
	 * them. Coarse enough to lose work, fine enough that the loss is measured in minutes.
	 */
	public int checkpointEvery = 25;

	/**
	 * Policy to resume from, or {@code null}.
	 *
	 * Separate from {@link #save} so a run can continue one policy into a different file, but by
	 * default a run overwrites its own checkpoint in place — resuming is then just running it again
	 * with {@code --resume}, with no path to remember.
	 */
	public File resume;

	/**
	 * Values loaded from the configuration file, resolved before the flags.
	 *
	 * <p>Null before {@link #parse} runs. Kept as a field rather than being copied into the individual
	 * fields so that {@link Trainer#main} has one object to hand to {@link
	 * com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO}, and so a test can assert on
	 * what the file said without reconstructing it from the fields that overwrote it.
	 */
	public PpoHyperparameters hyper;

	/**
	 * Whether {@code --metrics} and {@code --save} were named, so the derived defaults can tell an
	 * explicit path from one it chose itself.
	 *
	 * <p>Without this there is no way to re-derive a default after {@code --out} is known, because
	 * "the user set this" and "this happens to equal the default" are the same observation.
	 */
	private boolean metricsGiven, saveGiven;

	public TrainOptions(){
		metricsCsv = new File( workDir, "metrics.csv" );
		save = new File( workDir, "weights.bin" );
	}

	/**
	 * Parses the command line.
	 *
	 * <p>Two passes, and the order is the point. The first collects {@code --config} alone, because the
	 * file has to be loaded before the rest of the defaults are decided - otherwise a value in the file
	 * would either be overwritten by a flag nobody typed or overwrite a flag somebody did. The second
	 * pass applies the file and then the flags over the top of it.
	 *
	 * <p>A malformed configuration throws rather than exiting here. {@link Trainer#main} owns the
	 * reporting, and a parser that both reports and exits cannot be tested.
	 */
	public static TrainOptions parse( String[] args ) throws IOException {
		TrainOptions o = new TrainOptions();

		for (int i = 0; i < args.length; i++){
			if (args[ i ].equals( "--config" )){
				o.configFile = java.nio.file.Paths.get( args[ i + 1 ] );
				i++;
			}
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder.Loaded loaded =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder
						.load( o.configFile );

		PpoHyperparameters hyper = loaded.hyper;
		o.hyper = hyper;

		//the file's values become the new defaults, so a flag below still overrides them
		o.epochs = hyper.epochs;
		o.minibatchSize = hyper.minibatchSize;
		o.updateThreads = hyper.updateThreads;
		o.sampleRate = hyper.sampleRate;
		o.maxSampledPerEpisode = hyper.maxSampledPerEpisode;
		o.maxSamplesPerGeneration = hyper.maxSamplesPerGeneration;
		o.stallSeconds = hyper.stallSeconds;

		for (int i = 0; i < args.length; i++){
			switch (args[ i ]) {
				case "--config":     i++; break;
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
				case "--update-threads": o.updateThreads = Integer.parseInt( args[ ++i ] ); break;
				//these three existed as public Trainer fields with these defaults, but were never
				//reachable from the command line - so the knobs the plan documents could not be turned
				case "--sample-rate": o.sampleRate = Double.parseDouble( args[ ++i ] ); break;
				case "--max-sampled-per-episode":
					o.maxSampledPerEpisode = Integer.parseInt( args[ ++i ] ); break;
				case "--max-samples":
					o.maxSamplesPerGeneration = Integer.parseInt( args[ ++i ] ); break;
				case "--metrics":     o.metricsCsv = new File( args[ ++i ] ); o.metricsGiven = true; break;
				case "--save":        o.save = new File( args[ ++i ] ); o.saveGiven = true; break;
				case "--resume":      o.resume = new File( args[ ++i ] ); break;
				case "--checkpoint-every":
					o.checkpointEvery = Integer.parseInt( args[ ++i ] ); break;
				default:
					if (args[ i ].startsWith( "--" )){
						System.err.println( "[WARN] unknown option: " + args[ i ] );
					}
			}
		}
		o.workers = o.workers > 0 ? o.workers : defaultWorkerCount();
		o.episodes = o.episodes > 0 ? o.episodes : o.workers;
		if (o.checkpointEvery < 1) o.checkpointEvery = 1;

		//The sampling and batch fields above started as bare literals in three classes and are now read
		//from the configuration. The clamps stay, because a flag that sets them to zero is still a
		//mistake worth correcting rather than a value to honour literally - except the sample rate, where
		//zero is meaningful: the collector always retains an episode's last 20 steps, so 0 is how "off"
		//is expressed rather than a special case. See PpoHyperparameters.sampleRate.
		if (o.minibatchSize < 1) o.minibatchSize = 1;
		if (o.epochs < 1) o.epochs = 1;
		if (o.updateThreads < 0) o.updateThreads = 0;
		if (o.maxSampledPerEpisode < 1) o.maxSampledPerEpisode = 1;
		if (o.maxSamplesPerGeneration < 1) o.maxSamplesPerGeneration = 1;

		o.deriveOutputsUnder( o.workDir );
		return o;
	}

	/**
	 * Moves the default checkpoint and metrics file under {@code workDir}, for the ones nobody named.
	 *
	 * <p>Both are derived from {@link #workDir}, and both used to be derived in the constructor - which
	 * runs before the command line is read, so {@code --out} moved the working directory and left a
	 * 43 MB checkpoint and a metrics history behind in the old one. A run pointed at
	 * {@code --out D} reported saving to the default directory while its replays went to D, which is
	 * exactly the kind of split that makes a run's output impossible to find.
	 *
	 * <p>Only the ones nobody named move. An explicit {@code --save} or {@code --metrics} is a
	 * deliberate path and stays where it was asked for, which is why this runs after the flags rather
	 * than instead of them.
	 */
	private void deriveOutputsUnder( File dir ){
		if (!metricsGiven) metricsCsv = new File( dir, "metrics.csv" );
		if (!saveGiven) save = new File( dir, "weights.bin" );
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