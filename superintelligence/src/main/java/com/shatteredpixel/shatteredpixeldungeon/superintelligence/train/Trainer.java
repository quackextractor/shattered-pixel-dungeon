package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

	private final PPO ppo;
	private final WorkerPool pool = new WorkerPool();

	// per-seed best run, which is what gets saved as a replay
	private final Map<String, Replay> bestPerSeed = new HashMap<>();

	/** Epoch of the difficulty schedule, exposed so the logs can show progress. */
	private int epoch = 0;

	/** Episodes from the previous generation, which is what the seed gate reads. */
	private final List<Episode> lastEpisodes = new ArrayList<>();

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
		this.config = config;
		this.rng = new Random( seed );
		this.seeds = new SeedPool( rng );
		this.workDir = workDir;
		this.ppo = new PPO( config, rng );
	}

	public static void main( String[] args ){
		TrainOptions options = TrainOptions.parse( args );

		HeadlessServices.install( options.workDir );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		Trainer trainer = new Trainer( config, options.seed, options.workDir );

		try {
			trainer.pool.stallTimeoutMs( options.stallSeconds * 1000 );
			trainer.pool.launch( options.workers, options.javaHome, options.classpath,
					out -> trainer.writeParams( out ) );
			trainer.train( options.generations, options.episodes );
		} catch (IOException e){
			System.err.println( "[ERROR] training failed: " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
		} finally {
			trainer.pool.shutdown();
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

		//The worker computes advantages, so gamma and lambda have to travel with the weights. They
		//used to be PPO fields on each side with identical defaults - agreement by coincidence, and
		//it would have drifted the first time either side was tuned.
		out.writeFloat( ppo.gamma );
		out.writeFloat( ppo.lambda );
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

		System.out.println( Ansi.wrap( "machine", Ansi.DIM ) + "   "
				+ ResourceStats.availableProcessors() + " logical processors, pool of "
				+ pool.size() + " workers, stall timeout "
				+ ( pool.stallTimeoutMs() / 1000 ) + "s" );

		for (int g = 0; g < generations; g++){
			epoch = g;
			advanceSchedule();

			//every (seed, hero, wantReplay) is decided here, on this thread, before any worker is
			//asked. The seed pool and the RNG are shared mutable state, so handing work out first
			//and dispatching second is what keeps the concurrent dispatch below race-free while still
			//producing exactly the same assignment the sequential version did.
			episodesPerWorker = workerCount;
			List<Job> jobs = planGeneration( workerCount );

			ResourceStats.Interval gen = ResourceStats.start();
			List<Episode> episodes = dispatch( jobs );
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
				pushWeights();
				push.stop();

				lastBarrierSeconds = update.wallSeconds() + push.wallSeconds();
			}

			report( g, episodes, gen );
		}

		saveBestReplays();
	}

	/** One episode request, resolved before dispatch so workers never touch shared state. */
	private static class Job {
		final WorkerHandle worker;
		final String seed;
		final HeroClass heroClass;
		final boolean wantReplay;

		Job( WorkerHandle worker, String seed, HeroClass heroClass, boolean wantReplay ){
			this.worker = worker;
			this.seed = seed;
			this.heroClass = heroClass;
			this.wantReplay = wantReplay;
		}
	}

	/** Builds the generation's job list, round-robin so every worker gets the same count. */
	private List<Job> planGeneration( int workerCount ){
		List<Job> jobs = new ArrayList<>();

		for (WorkerHandle worker : pool.workers()){
			for (int i = 0; i < workerCount; i++){
				//every third seed is one worth keeping a replay of
				boolean wantReplay = (i % 3 == 0);
				String seed = seeds.next();
				HeroClass heroClass = HeroClass.values()[ rng.nextInt( HeroClass.values().length ) ];
				jobs.add( new Job( worker, seed, heroClass, wantReplay ) );
			}
		}

		return jobs;
	}

	/**
	 * Runs every job at once, one thread per worker.
	 *
	 * This was the difference between a full machine and one core. The previous loop asked each
	 * worker for its whole share in turn, so with eight workers seven of them were blocked on a
	 * pipe read while one simulated - the pool bought process isolation and nothing else, and the
	 * measured throughput was indistinguishable from a single worker.
	 *
	 * One thread per worker rather than a shared pool: each blocks on its own pipe for the whole
	 * episode, so the tasks are I/O-bound in the sense that matters here and there is nothing to
	 * gain from more threads than workers. Joining on all of them is what bounds the generation.
	 */
	private List<Episode> dispatch( List<Job> jobs ) throws IOException {
		Map<WorkerHandle, List<Job>> byWorker = new LinkedHashMap<>();
		for (Job job : jobs){
			byWorker.computeIfAbsent( job.worker, k -> new ArrayList<>() ).add( job );
		}

		List<Thread> threads = new ArrayList<>();
		List<List<Episode>> results = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (Map.Entry<WorkerHandle, List<Job>> entry : byWorker.entrySet()){
			final WorkerHandle worker = entry.getKey();
			final List<Job> mine = entry.getValue();
			final List<Episode> collected = new ArrayList<>();

			Thread thread = new Thread( () -> {
				try {
					collected.addAll( runOn( worker, mine ) );
				} catch (IOException e){
					failures.add( e );
				}
			}, "dispatch-" + worker.name );

			threads.add( thread );
			results.add( collected );
			thread.start();
		}

		//join, rather than poll on a timer: nothing here needs a cadence, and a fixed sleep would
		//only add latency proportional to however often it fired
		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();

		if (!failures.isEmpty()) throw failures.get( 0 );

		List<Episode> episodes = new ArrayList<>();
		for (List<Episode> collected : results) episodes.addAll( collected );
		return episodes;
	}

	/** Runs one worker's share of a generation. Called only from a dispatch thread. */
	private List<Episode> runOn( WorkerHandle worker, List<Job> jobs ) throws IOException {
		List<Episode> episodes = new ArrayList<>();

		DataOutputStream out = worker.dataOut;
		DataInputStream in = worker.dataIn;

		for (Job job : jobs){
			out.writeInt( Protocol.MSG_EPISODE );
			out.writeUTF( job.seed );
			out.writeUTF( job.heroClass.name() );
			out.writeBoolean( job.wantReplay );
			out.flush();

			int reply = in.readInt();
			if (reply != Protocol.MSG_EPISODE){
				throw new IOException( worker.name + " replied to an episode request with message "
						+ reply );
			}

			Episode episode = Episode.read( in );
			episode.seed = job.seed;
			episode.heroClass = job.heroClass.name();
			episode.workerName = worker.name;

			int steps = in.readInt();
			//the worker sends the step count always but the body only when a replay was requested,
			//so the body must only be read in that case. Reading it unconditionally deadlocked the
			//trainer against a worker that was already waiting for its next command.
			if (job.wantReplay) episode.replay = readReplay( in, steps );

			//the sampled transitions, decoded into this thread's own list and merged on the trainer
			//thread once every worker has joined. Nothing shared is touched from here.
			episode.transitions = Episode.readTransitions( in, config );

			//only after the whole frame has been consumed, so the watchdog sees progress rather
			//than a thread that has merely started reading
			pool.progress();

			episodes.add( episode );
		}

		return episodes;
	}

	private Replay readReplay( DataInputStream in, int steps ) throws IOException {
		Replay replay = new Replay();
		replay.seedText = in.readUTF();
		replay.heroClass = in.readUTF();
		replay.score = in.readDouble();
		replay.depth = in.readInt();
		replay.turns = in.readInt();

		for (int i = 0; i < steps; i++){
			Replay.Step step = new Replay.Step();
			step.action = in.readUTF();
			step.slot = in.readInt();
			step.mode = in.readUTF();
			step.heroPos = in.readInt();
			replay.steps.add( step );
		}
		return replay;
	}

	/**
	 * Pushes the updated policy to every worker.
	 *
	 * Sent as a full MSG_PARAMS frame, not a bare weight blob, and acknowledged. The two problems
	 * that fixes are worth naming because both were silent: the frame went out on the process's raw
	 * output stream while the episode path used a buffer over the same pipe, so buffered bytes and
	 * raw bytes could interleave out of order; and the blob carried no message header, so the
	 * worker parsed the first four bytes of a float as a message type and died with
	 * "unexpected message". The ack also means a push that a worker failed to absorb stops the run
	 * here instead of at some later, unrelated read.
	 */
	private void pushWeights() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream tmp = new DataOutputStream( buffer );
		writeParams( tmp );
		tmp.flush();
		final byte[] payload = buffer.toByteArray();

		//in parallel. A policy is ~14MB of floats, so pushing it to twenty workers one after
		//another means every worker sits at zero CPU for the whole barrier while the trainer
		//shuttles 280MB down pipes. Each worker owns its own pipe, so all of them can take it at
		//once and the barrier collapses to the slowest single transfer.
		List<Thread> threads = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (final WorkerHandle worker : pool.workers()){
			Thread thread = new Thread( () -> {
				try {
					worker.dataOut.write( payload );
					worker.dataOut.flush();
					int reply = worker.dataIn.readInt();
					if (reply != Protocol.MSG_PARAMS){
						throw new IOException( worker.name + " rejected a policy push, replied with "
								+ "message " + reply );
					}
				} catch (IOException e){
					failures.add( e );
				}
			}, "push-" + worker.name );
			threads.add( thread );
			thread.start();
		}

		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();
		if (!failures.isEmpty()) throw failures.get( 0 );

		pool.progress();
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

	private void report( int generation, List<Episode> episodes, ResourceStats.Interval gen ){
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
	}

	private float curriculumScale(){
		return 1f;
	}

	/** Keeps the best run per seed, which is what docs.md asks to be able to replay. */
	private void keepBest( Episode episode ){
		if (episode.replay == null) return;

		Replay existing = bestPerSeed.get( episode.replay.seedText );
		if (existing == null || episode.replay.score > existing.score){
			bestPerSeed.put( episode.replay.seedText, episode.replay );
		}
	}

	private void saveBestReplays(){
		File dir = new File( workDir, "replays" );
		int saved = 0;

		for (Map.Entry<String, Replay> entry : bestPerSeed.entrySet()){
			Replay replay = entry.getValue();
			replay.generation = epoch;
			try {
				ReplayIO.write( replay, new File( dir, entry.getKey().isEmpty()
						? "random.dat" : entry.getKey() + ".dat" ) );
				saved++;
			} catch (IOException e){
				System.err.println( "[WARN] could not save replay for seed "
						+ entry.getKey() + ": " + e.getMessage() );
			}
		}

		System.out.println();
		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   wrote " + saved
				+ " best-per-seed replays to " + dir.getPath() );
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