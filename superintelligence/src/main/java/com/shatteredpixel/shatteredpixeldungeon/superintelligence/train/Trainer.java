package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool.WorkerHandle;

/**
 * The training loop: launches worker JVMs, pools their episodes, and applies PPO updates.
 *
 * research.md: "Parallel Scaling: Since you plan to run up to 1,000 simulations at once, PPO can
 * effectively pool the gradients from all these simultaneous runs to make steady, reliable updates to
 * the policy without catastrophic forgetting."
 *
 * Workers are separate JVMs because the game's simulation state is entirely static - see
 * {@link Worker}. They speak a length-prefixed binary protocol over stdin and stdout; their human
 * readable log goes to stderr, so a stray print to stdout cannot desynchronise the stream. See
 * {@link Protocol}.
 *
 * The seed schedule from research.md's "Generalizing Across Seeds" is implemented here: one locked
 * seed until the agent clears the first boss, then ten, then a hundred, then fully random. See
 * {@link SeedPool}.
 *
 * Process lifetime, the stall watchdog and the console block are in {@link WorkerPool} and
 * {@link GenerationReport}; this class is the loop itself.
 */
public class Trainer {

	private final EnvConfig config;
	private final Random rng;
	private final SeedPool seeds;
	private final File workDir;

	/** Where {@code --trace-world} asks the workers to write their recording-side trace. */
	private final String traceWorldPath;

	/**
	 * Where this run's recordings go, when {@code --replay-dir} says so.
	 *
	 * <p>Null means {@code <--out>/replays}, which is where they went for the whole of this project's
	 * history and why a training run's output was nowhere the catalogue looks.
	 */
	private final File replayDirOverride;

	/**
	 * {@code --replay-group}: this run's recordings are written into a directory of this name, carry it
	 * in their header, and the catalogue lists them as a section of their own. Empty writes no
	 * subdirectory, which is where every recording made before groups existed lives.
	 */
	private final String replayGroup;

	private final PPO ppo;

	/**
	 * The channel to the worker processes.
	 *
	 * <p>Constructed after {@link #ppo} and {@link #config} because it carries the policy frame and
	 * decodes transitions against them; declared here rather than assigned in the constructor so the
	 * field order and the dependency agree.
	 */
	private final TrainerWorkers pool;

	// which of a run's episodes get written out: best per seed, plus the run's worst
	private final ReplayKeeper keeper = new ReplayKeeper();

	/**
	 * Send one episode's replay in every {@code recordEvery} jobs.
	 *
	 * <p>One, so every episode the worker plays is available to {@link ReplayKeeper}. The worker builds
	 * the whole replay regardless - {@code Worker.runEpisode} records every step and calls
	 * {@code recorder.end} - and this only decides whether the frame carries it, so a value above 1
	 * saves pipe traffic and nothing else. It used to be fixed at 3, which is a blind sample: the run
	 * whose one floor-2 episode scored -1,276 against a run-best of -2,778 did not have that episode in
	 * the catalog at all.
	 */
	private final int recordEvery;

	/** Epoch of the difficulty schedule, exposed so the logs can show progress. */
	private int epoch = 0;

	/** Episodes from the previous generation, which is what the seed gate reads. */
	private final List<Episode> lastEpisodes = new ArrayList<>();

	/**
	 * Per-generation metrics, or {@code null}.
	 *
	 * Until this existed the trainer kept one generation of episodes and nothing else, so a run's
	 * history had to be read out of console scrollback. See {@link MetricsHistory}.
	 */
	private MetricsHistory metrics;

	/** Whether a progress bar is currently on the last line without a newline after it. */
	private boolean barDrawn;

	/**
	 * Where the policy is checkpointed to, or {@code null} to save nothing.
	 *
	 * {@link #resumeFrom} is where a continued run reads from, which is deliberately the same file:
	 * a run overwrites its own checkpoint in place, so resuming is "run it again with
	 * {@code --resume}", with no path juggling.
	 */
	private File saveFile;
	private int checkpointEvery = 25;

	/** Progress restored from a checkpoint, or {@code null}. Reported, never acted on beyond the epoch. */
	private Checkpoint.Meta resumedFrom;

	/** Absolute generation number this run ends at, so the last generation always checkpoints. */
	private int lastGeneration;

	/** Highest generation already written to {@link #saveFile}, so a clean exit does not rewrite it. */
	private int lastSavedGeneration = -1;

	/** The trainer's seed, recorded in the checkpoint for provenance. */
	private long rngSeed;

	/** Wall time of the PPO update that ran after the previous generation, for the time split. */
	private ResourceStats.Interval lastUpdate;

	/**
	 * Summed CPU seconds the workers burned during the previous generation.
	 *
	 * Reported rather than measured locally because the trainer's own cores are almost idle while the
	 * workers run, so its process CPU time says nothing about how busy the machine is.
	 */
	private double lastWorkerSeconds;

	/** Episodes each worker was asked for, for the usage line. */
	private int episodesPerWorker;

	/** Per-worker cumulative CPU totals, differenced between reports. */
	private final Map<String, Double> lastCpuByWorker = new HashMap<>();

	/**
	 * Fraction of each worker's steps whose observation reaches the trainer.
	 *
	 * 5% of a generation is ~2,400 steps and about 118 MB. See PLAN-data-flow.md for why the bound
	 * that matters is the trainer's memory, not the pipe.
	 */
	public double sampleRate = 0.05;

	/**
	 * The rate actually sent to workers.
	 *
	 * Clamped below 1, and that is not a formality: {@link EpisodeCollector#TAIL_STEPS} always keeps
	 * an episode's last 20 steps, so on the short episodes an untrained policy produces - tens of turns,
	 * not the ~150 the arithmetic assumes - the tail alone is most of the episode. A requested 5% came
	 * out as 100% sampled before this, which is not a bug in the sampler but a real property of the
	 * tail policy interacting with short episodes, and silently sending the unclamped value would have
	 * made the sampled count drift for a reason no report would explain.
	 *
	 * Episodes are expected to get longer as the policy learns to survive, at which point the clamp
	 * stops binding and the requested rate is what applies.
	 */
	double sampleRate(){
		return Math.min( 1.0, Math.max( 0.0, sampleRate ) );
	}

	/** Hard ceiling on one episode's retained observations, ~98 MB. */
	public double maxSampledPerEpisode = 2048;

	int maxSampledPerEpisode(){
		return Math.max( EpisodeCollector.TAIL_STEPS,
				Math.min( Integer.MAX_VALUE, (int) maxSampledPerEpisode ) );
	}

	/**
	 * Hard ceiling on one generation's transitions in the update buffer, ~397 MB at 48.5 KB a step.
	 *
	 * This is the number that has to fit alongside the trainer's own heap and the workers'. See
	 * {@link #enforceGenerationCap}.
	 */
	public int maxSamplesPerGeneration = 8192;

	private static final int BOSS_DEPTH = 5;
	private static final int SEED_POOL_SIZE = 100;

	public Trainer( EnvConfig config, long seed, File workDir ){
		this( config, new PpoHyperparameters(), seed, workDir,
				new File( replayDirDefault(), "trainer-replays" ), null, "", 1 );
	}

	/**
	 * With the learning hyperparameters supplied up front, so a run cannot start with one set of values
	 * and apply another mid-flight.
	 *
	 * @param replayDir where recordings go, or null for {@code <workDir>/replays}
	 * @param traceWorldPath where workers write a recording-side world trace, or null for none
	 * @param replayGroup id the run's recordings are filed and listed under, or empty for none
	 * @param recordEvery send one episode's replay in every N jobs; 1 sends every episode
	 */
	public Trainer( EnvConfig config, PpoHyperparameters hyper, long seed, File workDir,
			File replayDir, String traceWorldPath, String replayGroup, int recordEvery ){
		this.config = config;
		this.rng = new Random( seed );
		this.rngSeed = seed;
		this.seeds = new SeedPool( rng );
		this.workDir = workDir;
		this.traceWorldPath = traceWorldPath;
		this.replayDirOverride = replayDir;
		this.replayGroup = replayGroup == null ? "" : replayGroup;
		this.recordEvery = Math.max( 1, recordEvery );
		this.ppo = new PPO( config, rng );
		this.pool = new TrainerWorkers( config, ppo );
		learning( hyper );
	}

	/**
	 * The environment settings this run is using, taken from the configuration file and the environment.
	 *
	 * <p>On the trainer rather than in {@link TrainOptions}, because {@code Trainer} is what actually
	 * builds the environment: the flags in {@code TrainOptions} govern the trainer's own machinery -
	 * workers, generations, checkpoints - and none of them describe the world the workers simulate.
	 * Keeping the two apart means a file can tune the reward function without touching the run schedule
	 * and vice versa, which is the separation a sweep needs.
	 *
	 * @param configFile the file named on the command line, or null for the compiled defaults
	 */
	static EnvConfig loadedConfig( java.nio.file.Path configFile ) throws IOException {
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder.Loaded loaded =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder
						.load( configFile );
		System.out.println( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder
				.describe( configFile, loaded.keysApplied ));
		return loaded.config;
	}

	/**
	 * The {@code --config} value, read before the full parse.
	 *
	 * <p>{@link TrainOptions#parse} finds this too, and reading it twice would mean a file loaded twice
	 * - with a warning printed twice, and a window in which the file could have changed. Duplicated
	 * because the environment config is needed before the options are parsed at all, and threading a
	 * partially-parsed object through to satisfy that would be worse. Four lines of argv scanning, not a
	 * second source of truth.
	 */
	private static java.nio.file.Path configFileIn( String[] args ){
		for (int i = 0; i < args.length - 1; i++){
			if (args[ i ].equals( "--config" )){
				return java.nio.file.Paths.get( args[ i + 1 ] );
			}
		}
		return null;
	}

	/** Batch shape, from the command line. Set before {@link #train}. */
	public void batch( int epochs, int minibatchSize ){
		ppo.epochs = epochs;
		ppo.minibatchSize = minibatchSize;
	}

	/** Update threads, from the command line. Set before {@link #train}. */
	public void updateThreads( int threads ){
		ppo.threads = threads;
	}

	/**
	 * Applies the learning hyperparameters, from the configuration file or the environment.
	 *
	 * <p>Only the values {@link PPO} reads but nothing on the command line can reach: gamma, lambda, the
	 * clip range and the entropy coefficient. The rest - epochs, minibatch, threads, sampling - are
	 * still applied separately, because each has a flag that must win over a file.
	 *
	 * <p>The learning rate is set on the network as well as the learner. It is one value with two
	 * consumers, and {@code PPO.update} copies it onto the network before stepping - so setting it here
	 * would be redundant if the network were not also constructed with it, which it is. This exists so
	 * that a configured learning rate cannot be accepted and then ignored.
	 */
	public void learning( PpoHyperparameters hyper ){
		ppo.gamma = hyper.gamma;
		ppo.lambda = hyper.lambda;
		ppo.clipEpsilon = hyper.clipEpsilon;
		ppo.entropyCoeff = hyper.entropyCoeff;
		ppo.learningRate = hyper.learningRate;
		ppo.network.learningRate = hyper.learningRate;
	}

	/**
	 * Releases the update's thread pool.
	 *
	 * <p>Called on the way out of a run. The threads are daemons, so they would not hold the JVM open,
	 * but each holds a whole {@code Network} - 14.3 MB of parameters plus scratch - and on a machine
	 * with a 2 GB pagefile that is not left to the garbage collector's discretion.
	 */
	public void closeUpdate(){
		ppo.close();
	}

	/** Sampling knobs, from the command line. Set before {@link #train}. */
	public void sampling( double rate, int perEpisode, int perGeneration ){
		this.sampleRate = rate;
		this.maxSampledPerEpisode = perEpisode;
		this.maxSamplesPerGeneration = perGeneration;
	}

	/**
	 * Per-generation metrics, or {@code null} to keep the old behaviour of discarding them.
	 *
	 * Set before {@link #train}. This is what a run's history is written to; without it a run's
	 * figures exist only in console scrollback, which is why {@link MetricsHistory} exists.
	 */
	public void history( MetricsHistory metrics ){
		this.metrics = metrics;
	}

	/**
	 * Loads a policy before any worker is launched.
	 *
	 * <p>Before {@link #train}, and deliberately not inside it: a worker must never see a policy the
	 * trainer has not finished reading, and the read has to fail the run before a pool of processes
	 * is holding 1.2 GB each.
	 *
	 * @return what the checkpoint recorded, for the console line, or {@code null} if {@code file} is
	 *         {@code null}
	 */
	public Checkpoint.Meta resume( File file ) throws IOException {
		if (file == null) return null;

		if (!file.isFile()){
			throw new FileNotFoundException( "--resume " + file
					+ " does not exist. Pass a file written by a previous run, or omit the flag to"
					+ " start from a fresh random policy." );
		}

		Checkpoint.Meta meta = Checkpoint.load( file, ppo.network, config );

		//the epoch counter continues, so a resumed run's generations do not overwrite the CSV rows
		//of the run it is continuing, and so the checkpoint records true progress
		epoch = meta.generation;
		resumedFrom = meta;
		//the resumed policy is on disk, so exiting without a new save must not look like progress lost
		lastSavedGeneration = meta.generation;

		System.out.println( Ansi.wrap( "resume", Ansi.DIM ) + "   loaded " + file
				+ "  " + Ansi.wrap( "(" + meta + ")", Ansi.DIM ) );
		return meta;
	}

	/**
	 * Where the policy is checkpointed to, and how often.
	 *
	 * {@code saveFile} of {@code null} disables checkpointing entirely, which is the right default for
	 * a smoke test and the wrong one for anything else — a run that saves nothing cannot be resumed
	 * or compared, which is why this is set explicitly rather than defaulted to a location.
	 */
	public void checkpointing( File saveFile, int everyGenerations ){
		this.saveFile = saveFile;
		this.checkpointEvery = Math.max( 1, everyGenerations );
	}

	public static void main( String[] args ){
		TrainOptions options;
		EnvConfig config;
		try {
			//config first: TrainOptions.parse reads the same file for the learning hyperparameters, and
			//doing it the other way round would mean loading it twice and could report two different
			//configurations if the file changed between them
			config = loadedConfig( configFileIn( args ) );
			options = TrainOptions.parse( args );
		} catch (IOException | IllegalArgumentException e){
			//A bad configuration is refused rather than defaulted, so this is the end of the command and
			//not a warning beside a run that used numbers nobody asked for.
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		HeadlessServices.install( options.workDir );
		HeadlessServices.disableSaving( true );

		//after the constructor applies the file, because a flag typed on the command line has to win over
		//it - and this one decides what every episode in the run is worth
		if (options.rewardProfile() != null){
			config.rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
					.RewardProfile.parse( options.rewardProfile() );
			System.out.println( "reward   profile " + config.rewardProfile.key()
					+ " (--reward-profile)" );
		}

Trainer trainer = new Trainer( config, options.hyper, options.seed, options.workDir,
				options.replayDir, options.traceWorld, options.replayGroup, options.recordEvery );

		try {
			trainer.pool.stallTimeoutMs( options.stallSeconds * 1000 );
			//after the constructor, because these are the flag-resolved values: the constructor applied the file's,
			//and a flag typed on the command line has to win over it
			trainer.batch( options.epochs, options.minibatchSize );
			trainer.updateThreads( options.updateThreads );
			trainer.sampling( options.sampleRate, options.maxSampledPerEpisode,
					options.maxSamplesPerGeneration );
			trainer.history( new MetricsHistory( options.metricsCsv ));
			trainer.checkpointing( options.save, options.checkpointEvery );

			//before the pool exists: a bad checkpoint should cost a second, not a gigabyte of workers
			trainer.resume( options.resume );

			trainer.pool.launch( options.workers, options.javaHome, options.classpath,
					out -> trainer.writeParams( out ) );
			trainer.train( options.generations, options.episodes );
		} catch (IOException e){
			System.err.println( "[ERROR] training failed: " + e.getMessage() );
			//the stack, because the message alone was not enough to diagnose a real failure: a 16-worker
			//run on a 32 GB machine reported only "Insufficient system resources exist to complete the
			//requested service", which is a Windows error code with no indication of which call wanted
			//the memory. Cheap to print and the difference between a diagnosis and a guess.
			e.printStackTrace();
			System.exit( 1 );
		} finally {
			//a normal exit checkpoint, so a run that finished on a non-multiple of the interval still
			//holds its final policy. afterExit rather than in the loop's own path, so this also covers
			//the generations that completed before a failure aborted the run
			try {
				trainer.saveOnExit();
			} catch (RuntimeException ignored){
				//never mask the original failure with a cleanup one
			}
			trainer.pool.shutdown();
			trainer.closeUpdate();
			if (trainer.metrics != null) trainer.metrics.close();
		}
	}

	// --------------------------------------------------------------------------- params

	private void writeParams( DataOutputStream out ) throws IOException {
		out.writeInt( Protocol.MSG_PARAMS );
		out.writeInt( config.maxSlots );
		out.writeInt( config.gridWidth );
		out.writeInt( config.gridHeight );
		out.writeInt( config.turnLimitPerFloor );
		out.writeInt( config.turnLimitTotal );
		out.writeFloat( config.depthReward );
		out.writeFloat( config.turnCost );
		out.writeFloat( config.deathPenalty );
		out.writeFloat( config.victoryReward );
		out.writeInt( config.stallLimit );

		//A UTF rather than an enum ordinal, because a worker reading an ordinal has no way to say which
		//profile it got - and this profile decides every episode's score. Matching where Worker reads it.
		out.writeUTF( config.rewardProfile.key() );

		//The worker computes advantages, so gamma and lambda have to travel with the weights. They
		//used to be PPO fields on each side with identical defaults - agreement by coincidence, and
		//it would have drifted the first time either side was tuned.
		out.writeFloat( ppo.gamma );
		out.writeFloat( ppo.lambda );


		//A diagnostic, and the only thing that travels a *path* to a worker rather than a value.
		//Each worker writes the same filename, so this is one trace of whichever episode the worker
		//happened to be running - which is all the question needs, because the question is whether
		//the recording path and the replay path agree, not which worker recorded.
		//
		//After gamma and lambda, matching where Worker reads it. Putting it anywhere else desynchronises
		//the frame, and the failure is a worker that dies on the next readFloat with a
		//UTFDataFormatException naming no field - which is how it was found.
		out.writeUTF( traceWorldPath == null ? "" : traceWorldPath );
		//A diagnostic, and the only thing that travels a *path* to a worker rather than a value.
		//Each worker writes the same filename, so this is one trace of whichever episode the worker
		//happened to be running - which is all the question needs, because the question is whether the
		//recording path and the replay path agree, not which worker recorded.
		out.writeFloat( (float) sampleRate() );
		out.writeInt( (int) maxSampledPerEpisode() );

		out.writeLong( rng.nextLong() );
		Worker.writeWeights( out, ppo.network );
	}

	// --------------------------------------------------------------------------- training

	/**
	 * Refuses to grow the update buffer past {@link #maxSamplesPerGeneration}.
	 *
	 * The per-episode cap bounds one worker; this bounds the generation. At 20 workers the two are a
	 * factor of 320 apart, and an episode that runs long enough to hit its cap is enough on its own to
	 * put the trainer into swap - the machine's pagefile is 2 GB, so that does not degrade gracefully,
	 * it thrashes.
	 *
	 * The oldest are dropped, so the tail of the generation survives: those are the truncated episodes
	 * and the terminal ones, which carry the termination signal that an episode cut short does not.
	 */
	private void enforceGenerationCap(){
		int over = ppo.bufferSize() - maxSamplesPerGeneration;
		if (over <= 0) return;

		ppo.dropOldest( over );
		droppedThisGeneration += over;

		System.err.println( "[WARN] generation " + epoch + ": " + over + " transitions over the "
				+ maxSamplesPerGeneration + " cap were dropped. Raise --max-samples or lower"
				+ " --sample-rate; the update saw only the most recent " + maxSamplesPerGeneration
				+ "." );
	}

	/** Transitions discarded by {@link #enforceGenerationCap} this generation, for the report. */
	private int droppedThisGeneration;

	//read before the update consumes the buffer, since afterwards it is always zero
	private int lastSampledSteps;
	private double lastAdvantageMean;
	private double lastAdvantageStd;

	/**
	 * Runs {@code generations} rounds of rollout-then-update.
	 *
	 * Each generation asks every worker for one episode, pools them into one update, and pushes the
	 * new policy back out. Asking for exactly one episode per worker per generation keeps the
	 * buffer's composition predictable, which matters because an episode can end anywhere from
	 * fifty to several thousand steps.
	 */
	public void train( int generations, int workerCount ) throws IOException {
		seeds.fill( SEED_POOL_SIZE );
		pool.startWatchdog();

		if (metrics != null) metrics.open();

		//A resumed run continues counting from where the checkpoint left off, so its generations are
		//the same numbering as the run it continues and its CSV rows do not collide with them. The
		//count is not the same run: the trainer's RNG restarts, so seeds are drawn afresh rather than
		//picking up the schedule where it stopped. Recorded rather than hidden.
		int first = resumedFrom == null ? 0 : resumedFrom.generation;
		this.lastGeneration = first + generations;
		long startedAt = System.currentTimeMillis();

		System.out.println( Ansi.wrap( "machine", Ansi.DIM ) + "   "
				+ ResourceStats.availableProcessors() + " logical processors, pool of "
				+ pool.size() + " workers, stall timeout "
				+ ( pool.stallTimeoutMs() / 1000 ) + "s" );
		if (resumedFrom != null){
			System.out.println( Ansi.wrap( "resuming", Ansi.DIM ) + "   generations "
					+ first + " to " + lastGeneration
					+ Ansi.wrap( "  (seeds restart from the trainer seed; the policy does not)", Ansi.DIM ) );
		}

		for (int g = first; g < lastGeneration; g++){
			epoch = g;
			advanceSchedule();

			//every (seed, hero, wantReplay) is decided here, on this thread, before any worker is
			//asked. The seed pool and the RNG are shared mutable state, so handing work out first
			//and dispatching second is what keeps the concurrent dispatch below race-free while still
			//producing exactly the same assignment the sequential version did.
			episodesPerWorker = workerCount;
			List<TrainerWorkers.Job> jobs = planGeneration( workerCount );

			ResourceStats.Interval gen = ResourceStats.start();
			List<Episode> episodes = pool.dispatch( jobs );
			gen.stop();
			lastWorkerSeconds = sumWorkerCpuSeconds( episodes );

			//merged here rather than in the dispatch threads: this is the first point at which one
			//thread owns the learner again, and PPO's buffer is not thread-safe
			for (Episode e : episodes) ppo.addAll( e.transitions );
			enforceGenerationCap();

			//read before the update, which consumes the buffer. Read afterwards it is always zero, which
			//is exactly what it read before the workers started returning transitions - so the one
			//number that says the whole design is working was structurally incapable of ever moving.
			lastSampledSteps = ppo.bufferSize();
			lastAdvantageMean = ppo.meanAdvantage();
			lastAdvantageStd = ppo.advantageStdDev();

			if (!episodes.isEmpty()){
				ResourceStats.Interval update = ResourceStats.start();
				ppo.update();
				update.stop();
				lastUpdate = update;

				//the push is a barrier: no worker can run while weights are moving, so its cost is
				//reported next to the update's rather than folded into the collection figure
				ResourceStats.Interval push = ResourceStats.start();
				pool.pushWeights();
				push.stop();

				lastBarrierSeconds = update.wallSeconds() + push.wallSeconds();
			}

			report( g, episodes, gen );

			//after the report, so a checkpoint failure is reported next to the figures that produced it
			maybeCheckpoint( g + 1 );

			clearProgressBar();
			drawProgressBar( g + 1 - first, generations, startedAt );
		}

		clearProgressBar();

		saveBestReplays();

		//after the replays, so a failure to write them does not cost the history
		if (metrics != null) metrics.printTrend();
	}

	/**
	 * Rewrites one line in place, so a run of an hour looks like a run and not like a hang.
	 *
	 * <p>No flushing per generation: {@code System.out} is line-buffered when it is a terminal and block
	 * buffered when it is a log file, and a bar that flushes twice a minute is not what anyone is reading.
	 * A log file therefore holds the last bar rather than every bar, which is the right loss - the
	 * per-generation block {@code report} prints is the record, and that is flushed as it goes.
	 */
	private void drawProgressBar( int done, int total, long startedAt ){
		double meanScore = Double.NaN;
		int bestDepth = 0;

		if (metrics != null){
			//the last row the history holds, which is this generation's: read rather than recomputed, so
			//the bar cannot report a different number from the block above it
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.MetricsHistory.Snapshot last =
					metrics.latest();
			if (last != null){
				meanScore = last.meanScore;
				bestDepth = last.bestDepth;
			}
		}

		System.out.print( ProgressBar.render( done, total, startedAt, meanScore, bestDepth ) );
		barDrawn = true;
	}

	/**
	 * Ends the bar's line, once, before anything else is printed.
	 *
	 * <p>The bar is rewritten in place with a carriage return, so whatever prints next would otherwise
	 * land on top of it - which is what a log file records, and it is unreadable. Called from the places
	 * that print outside the generation's own block rather than from {@code System.out}, which cannot
	 * know.
	 */
	private void clearProgressBar(){
		if (!barDrawn) return;
		System.out.println();
		barDrawn = false;
	}

	/**
	 * Writes the policy if this generation is a checkpoint generation, and always on the last one.
	 *
	 * <p>The last generation is unconditional on purpose. Checkpointing every N generations means a
	 * run whose length is not a multiple of N ends holding a policy several generations stale, which
	 * is the case that actually gets resumed.
	 *
	 * <p>A failure here is reported and does not stop the run. The policy is a derived artefact — it
	 * can always be retrained — whereas losing the process over a full disk would cost the whole run.
	 */
	private void maybeCheckpoint( int generation ){
		if (saveFile == null) return;
		if (generation % checkpointEvery != 0 && generation != lastGeneration) return;

		writeCheckpoint( generation );
	}

	/**
	 * Writes the final policy when the run ends, however it ended.
	 *
	 * <p>Guarded so it cannot overwrite a checkpoint with the same policy: after a clean periodic
	 * checkpoint the last generation is already saved, and writing again would be a redundant 43 MB
	 * and a confusing extra line in the log.
	 */
	public void saveOnExit(){
		if (saveFile == null) return;
		if (lastSavedGeneration >= lastGeneration) return;
		writeCheckpoint( lastGeneration );
	}

	/** Writes the policy and says so, with the path, because a silent save is indistinguishable from no save. */
	void writeCheckpoint( int generation ){
		if (saveFile == null) return;

		try {
			Checkpoint.save( saveFile, ppo.network, config, generation, rngSeed );
			lastSavedGeneration = generation;
			System.out.println( Ansi.wrap( "saved", Ansi.DIM ) + "   " + saveFile
					+ "  " + Ansi.wrap( "(generation " + generation + ", adam step "
					+ ppo.network.adamSteps() + ")", Ansi.DIM ) );
		} catch (IOException e){
			System.err.println( "[ERROR] could not write " + saveFile + ": " + e.getMessage() );
			System.err.println( "        This run's policy will be lost. Nothing else is affected." );
		}
	}

	/**
	 * Builds the generation's job list, round-robin so every worker gets the same count.
	 *
	 * <p>The schedule is the trainer's, not the channel's: which seed, which hero and which of these
	 * is worth keeping a replay of are all decisions about what the agent should be asked to do. The
	 * resulting {@link TrainerWorkers.Job}s are opaque to the channel, which only carries them.
	 */
	private List<TrainerWorkers.Job> planGeneration( int workerCount ){
		List<TrainerWorkers.Job> jobs = new ArrayList<>();

		for (WorkerHandle worker : pool.workers()){
			for (int i = 0; i < workerCount; i++){
				//every episode, unless told otherwise. This was every third, and that made "best per
				//seed" mean best of the one in three that happened to be sent - so a run whose one floor-2
				//episode scored four times anything else it did simply did not have it in the catalog.
				//The worker builds the whole replay either way and only skips the transmission, so the
				//sample paid the entire recording cost and saved nothing but the bytes.
				boolean wantReplay = (i % recordEvery) == 0;
				String seed = seeds.next();
				HeroClass heroClass = HeroClass.values()[ rng.nextInt( HeroClass.values().length ) ];
				jobs.add( pool.job( worker, seed, heroClass, wantReplay ));
			}
		}

		return jobs;
	}

	/**
	 * Advances research.md's seed schedule.
	 *
	 * One locked seed until the agent clears floor 5, then ten, then a hundred. The gate is
	 * measured depth rather than elapsed generations so it tracks actual progress.
	 */
	private void advanceSchedule(){
		int best = 0;
		for (Episode e : lastEpisodes) best = Math.max( best, e.depth );

		int target;
		if (best <= BOSS_DEPTH){
			target = 1;
		} else if (best <= BOSS_DEPTH + 5){
			target = 10;
		} else {
			target = SEED_POOL_SIZE;
		}
		seeds.activeSeeds( target );
	}

	/** Seconds the last generation spent on the update and the weight push, where no worker runs. */
	private double lastBarrierSeconds;

	// --------------------------------------------------------------------------- reporting

	/**
	 * Tally one episode's termination reason into the report.
	 *
	 * <p>The worker sends {@code env.endReason().name()}, and the ordinal is recovered here rather
	 * than sent across the wire: adding a reason to the enum would otherwise silently renumber the
	 * array and every historical CSV row would shift meaning.
	 *
	 * <p>An unrecognised name is counted under {@code OTHER} and warned about once per generation.
	 * Silently dropping it would make a broken worker's episodes vanish from the breakdown, which is
	 * worse than showing them in the wrong bucket — and this number is what a future regression is
	 * going to be read from.
	 */
	private void countEndReason( GenerationReport.Snapshot s, String reason ){
		if (reason == null || reason.isEmpty()){
			s.endReasons[ RewardModel.TerminateReason.OTHER.ordinal() ]++;
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel.TerminateReason[]
				reasons = RewardModel.TerminateReason.values();
		for (int i = 0; i < reasons.length; i++){
			if (reasons[ i ].name().equals( reason )){
				s.endReasons[ i ]++;
				return;
			}
		}

		s.endReasons[ RewardModel.TerminateReason.OTHER.ordinal() ]++;
		if (!warnedUnknownReason){
			warnedUnknownReason = true;
			System.err.println( "[WARN] a worker reported the end reason '" + reason
					+ "', which this build does not know. Counted as OTHER; the trainer and workers are"
					+ " probably from different builds." );
		}
	}

	private boolean warnedUnknownReason;

	private void report( int generation, List<Episode> episodes, ResourceStats.Interval gen ){
		clearProgressBar();
		lastEpisodes.clear();
		lastEpisodes.addAll( episodes );

		if (episodes.isEmpty()) return;

		GenerationReport.Snapshot s = new GenerationReport.Snapshot();
		s.generation = generation;
		s.episodes = episodes.size();
		s.activeSeeds = seeds.activeSeeds();
		s.totalSeeds = seeds.totalSeeds();
		s.shaping = curriculumScale();

		long totalTurns = 0;
		int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;
		Episode bestEpisode = null;

		for (Episode e : episodes){
			s.meanScore += e.score;
			s.meanDepth += e.depth;
			s.meanTurns += e.turns;
			totalTurns += e.turns;
			best = Math.max( best, (int) e.score );
			worst = Math.min( worst, (int) e.score );
			if (bestEpisode == null || e.score > bestEpisode.score) bestEpisode = e;
			if (e.replay != null) keepBest( e );

			countEndReason( s, e.reason );
		}

		int n = episodes.size();
		s.meanScore /= n;
		s.meanDepth /= n;
		s.meanTurns /= n;
		s.bestScore = best;
		s.worstScore = worst;
		s.bestDepth = bestEpisode.depth;
		s.totalTurns = totalTurns;

		s.policyLoss = ppo.lastPolicyLoss;
		s.valueLoss = ppo.lastValueLoss;
		s.entropy = ppo.lastEntropy;
		s.clipFraction = ppo.lastClipFraction;
		s.klDivergence = ppo.lastKLDivergence;

		double wall = gen.wallSeconds();
		s.wallSeconds = wall;
		s.ppoSeconds = lastUpdate == null ? 0 : lastUpdate.wallSeconds();
		s.barrierSeconds = lastBarrierSeconds;
		s.turnsPerSecond = ResourceStats.stepsPerSecond( totalTurns, wall );
		s.episodesPerSecond = n / Math.max( 1e-9, wall );

		//workerSeconds is a summed CPU total across every episode and every worker. It is kept as a
		//cross-check on the system figure rather than used to derive one, because differencing a
		//coarse per-process counter across short episodes loses about half the signal.
		s.workerCores = wall <= 0 ? 0 : lastWorkerSeconds / wall;
		s.trainerCores = Math.max( 0, gen.coresUsed() );

		s.systemLoad = ResourceStats.systemCpuLoad();
		s.logicalProcessors = ResourceStats.availableProcessors();
		s.poolSize = pool.size();
		s.episodesPerWorker = episodesPerWorker;

		s.sampledSteps = lastSampledSteps;
		s.droppedSteps = droppedThisGeneration;
		s.advantageMean = lastAdvantageMean;
		s.advantageStd = lastAdvantageStd;

		//measured off the transitions that actually arrived, rather than computed from the expected
		//count: a sample rate that is not doing what it says shows up here as a smaller number
		s.sampledBytes = 0;
		s.gridBytes = 0;
		for (Episode e : episodes){
			for (com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition t : e.transitions){
				s.gridBytes += t.grid.length;
				s.sampledBytes += t.grid.length
						+ 4L * (t.inventory.length + t.hero.length + t.actionMask.length
						+ t.slotMask.length + t.targetMask.length);
			}
		}

		//reset here rather than at the top of the next generation, so the figure printed beside this
		//generation's data is this generation's drop
		droppedThisGeneration = 0;

		GenerationReport.print( s );

		//recorded after the console block, so the report still leads and the file catches up
		if (metrics != null) metrics.record( s );
	}

	private float curriculumScale(){
		return 1f;
	}

	/** Offers an episode to the keeper: the best per seed, plus the run's single worst. */
	private void keepBest( Episode episode ){
		keeper.consider( episode.replay );
	}

private void saveBestReplays(){
		clearProgressBar();
		File dir = replayDir();
		int saved = 0;

		//Record where these landed before writing any of them. A recording in a directory nothing knows
		//to look in is not a recording: the viewer and the catalog searched a fixed pair of paths and
		//found 5 of the 147 on this machine, missing the rest without a word.
		//
		//The directory recorded is the one the flag named, not the group subdirectory under it. The
		//catalog scans a directory's subdirectories, so indexing the group would find these files twice
		//- once as part of the parent and once as a directory of their own - and every grouped seed
		//would report that it was recorded twice.
		replayDirBase().mkdirs();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIndex.record( replayDirBase() );

		for (Replay replay : keeper.keep()){
			replay.generation = epoch;
			//Stamped here rather than sent down the worker pipe, because the group is a property of the
			//run rather than of any episode - the worker never learns it. Same shape as the generation
			//above, and for the same reason the reward profile was once a codec field: a field that
			//exists on both sides of a pipe has to be written on one of them.
			replay.group = replayGroup;

			//**Never overwrite.** One file per seed per run, and a second run of the same seed wrote
			//straight over the first - so a directory that was meant to accumulate training history
			//held only the newest run, and the earlier recordings were gone with nothing saying so. The
			//cost is filenames like `MFS-CDV-GCN-r2.dat`: an unreadable suffix on a file nobody types by
			//hand, against silently losing the recording a run was pointed at.
			File file = uniqueFile( dir, replay.seedText );
			try {
				ReplayIO.write( replay, file );
				saved++;
			} catch (IOException e){
				System.err.println( "[WARN] could not save replay for seed "
						+ replay.seedText + ": " + e.getMessage() );
			}
		}

		System.out.println();
		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   wrote " + saved
				+ " replays from " + keeper.considered() + " episodes (best per seed, plus the run's"
				+ " worst) to " + dir.getPath()
				+ ( replayGroup.isEmpty() ? "" : "  (group " + replayGroup + ")" ) );
	}

	/**
	 * Where a run's recordings go.
	 *
	 * <p>{@code <--out>/replays} unless told otherwise. {@code --out} is the run's working directory -
	 * checkpoints, metrics and the weight file all live under it - and a recording written into it is
	 * then somewhere the catalogue does not read, which is how 142 of 147 recordings on this machine were
	 * invisible. {@code --replay-dir} says where the recordings specifically go, and defaults to the
	 * repository's {@code trainer-replays}, which is a directory the catalogue already reads.
	 */
	/**
	 * The module directory, found by walking up from here.
	 *
	 * <p>Used as the parent of the default replay destination, so a training run writes where the
	 * catalogue reads without being told. Resolved by walking rather than by a relative path because the
	 * working directory is the module's for a gradle task and the repository root for a direct launch, and
	 * the two disagree.
	 */
	static File replayDirDefault(){
		File here = new File( "." ).getAbsoluteFile();
		for (int i = 0; i < 6 && here != null; i++){
			if (new File( here, "superintelligence.properties" ).isFile()) return tidy( here );
			//the repository root as well as the module directory: a command launched from the root used to
			//fall through to the caller's own working directory and write out/ there, which is not the
			//directory the catalogue and the index were told about
			if (new File( here, "superintelligence/build.gradle" ).isFile()){
				return tidy( new File( here, "superintelligence" ) );
			}
			here = here.getParentFile();
		}
		//not found: the caller's own working directory, which is where a relative path would have gone
		return tidy( new File( "." ).getAbsoluteFile() );
	}

	/**
	 * Where a run's weights, checkpoints and metrics go when nobody says otherwise.
	 *
	 * <p>{@code <module>/out/run}, not the system temp directory. Temp is emptied on a schedule nobody
	 * controls, and a run's evidence - the metrics CSV that says what a run learned, the checkpoint that
	 * lets it be resumed - is worth exactly nothing after that. The directory is gitignored, so an
	 * artefact there costs the repository nothing.
	 */
	public static File outDirDefault(){
		return new File( replayDirDefault(), "out/run" );
	}

	/**
	 * Where a measurement worth keeping goes: the per-generation metrics CSV.
	 *
	 * <p>Separate from {@link #outDirDefault()} because the two are read by different people at different
	 * times - a checkpoint to resume from, a CSV to answer "what did that run learn" - and because the
	 * rule in {@code AGENTS.md} asks for evidence in one directory rather than beside whatever produced it.
	 */
	public static File evidenceDirDefault( String name ){
		return new File( new File( replayDirDefault(), "out/evidence" ), name );
	}

	/**
	 * The path without a {@code .} in it.
	 *
	 * <p>{@code getAbsoluteFile} leaves the {@code .} from {@code new File( "." )} in, so the resolved
	 * destination was {@code superintelligence/./trainer-replays} while the catalogue's own default spelled
	 * the same directory {@code superintelligence/trainer-replays}. The two are the same directory and two
	 * different strings, and the catalogue's deduplication compares strings — so every trainer recording
	 * was listed twice. Canonicalisation touches the filesystem and can fail, hence the fallback.
	 */
	private static File tidy( File dir ){
		try {
			return dir.getCanonicalFile();
		} catch (java.io.IOException e){
			return dir.getAbsoluteFile();
		}
	}

	private File replayDirBase(){
		return replayDirOverride != null ? replayDirOverride : new File( workDir, "replays" );
	}

	/** The base, plus a directory named after the group when one was given. */
	private File replayDir(){
		return replayGroup.isEmpty() ? replayDirBase() : new File( replayDirBase(), replayGroup );
	}

	/**
	 * A free filename for {@code seed}, never one that already exists.
	 *
	 * <p>Appended {@code -r2}, {@code -r3} and so on. The empty seed - a random-seed episode, which
	 * {@code SeedPool} releases 10% of the time - writes {@code random} and gets the same treatment.
	 */
	private static File uniqueFile( File dir, String seed ){
		String stem = seed.isEmpty() ? "random" : seed;
		File file = new File( dir, stem + ".dat" );
		int run = 2;
		while (file.exists() && run < 10000){
			file = new File( dir, stem + "-r" + run + ".dat" );
			run++;
		}
		return file;
	}

	/**
	 * CPU seconds the pool burned during the previous generation.
	 *
	 * Workers report a cumulative total rather than a per-episode cost, and the trainer differences
	 * consecutive reports per worker. Summed deltas, this is the honest measure of how much of the
	 * machine the simulation occupied; the trainer's own counters are near zero throughout, because
	 * its cores are idle while the workers run.
	 */
	private double sumWorkerCpuSeconds( List<Episode> episodes ){
		double total = 0;
		for (Episode e : episodes){
			if (e.workerCpuTotal < 0 || e.workerName == null) continue;
			Double previous = lastCpuByWorker.get( e.workerName );
			if (previous != null){
				double delta = e.workerCpuTotal - previous;
				if (delta > 0) total += delta;
			}
			lastCpuByWorker.put( e.workerName, e.workerCpuTotal );
		}
		return total;
	}
}
