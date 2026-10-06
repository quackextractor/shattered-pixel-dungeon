package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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

/**
 * The training loop: launches worker JVMs, pools their episodes, and applies PPO updates.
 *
 * research.md: "Parallel Scaling: Since you plan to run up to 1,000 simulations at once, PPO can
 * effectively pool the gradients from all these simultaneous runs to make steady, reliable updates
 * to the policy without catastrophic forgetting."
 *
 * Workers are separate JVMs because the game's simulation state is entirely static - see
 * {@link Worker}. They speak a length-prefixed binary protocol over stdin and stdout; their human
 * readable log goes to stderr, so a stray print to stdout cannot desynchronise the stream.
 *
 * The seed schedule from research.md's "Generalizing Across Seeds" is implemented here: one locked
 * seed until the agent clears the first boss, then ten, then a hundred, then fully random. See
 * {@link SeedPool}.
 */
public class Trainer {

	private final EnvConfig config;
	private final Random rng;
	private final SeedPool seeds;
	private final File workDir;

	private final PPO ppo;
	private final List<WorkerHandle> workers = new ArrayList<>();

	// per-seed best run, which is what gets saved as a replay
	private final Map<String, Replay> bestPerSeed = new HashMap<>();

	/** Epoch of the difficulty schedule, exposed so the logs can show progress. */
	private int epoch = 0;

	public Trainer( EnvConfig config, long seed, File workDir ){
		this.config = config;
		this.rng = new Random( seed );
		this.seeds = new SeedPool( rng );
		this.workDir = workDir;
		this.ppo = new PPO( config, rng );
	}

	public static void main( String[] args ){
		Options options = Options.parse( args );

		HeadlessServices.install( options.workDir );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		Trainer trainer = new Trainer( config, options.seed, options.workDir );

try {
		trainer.stallTimeoutMs = options.stallSeconds * 1000;
		trainer.launchWorkers( options.workers, options.javaHome, options.classpath );
		trainer.train( options.generations, options.episodes );
		} catch (IOException e){
			System.err.println( "[ERROR] training failed: " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
		} finally {
			trainer.shutdown();
		}
	}

	// --------------------------------------------------------------------------- workers

	/** Root of the per-worker scratch directories. */
	private static File workerDir(){
		return new File( System.getProperty( "java.io.tmpdir" ), "spd-train" );
	}

	/** Starts {@code count} worker JVMs and hands each the current policy. */
	public void launchWorkers( int count, String javaHome, String classpath ) throws IOException {
		String java = (javaHome == null || javaHome.isEmpty())
				? join( System.getProperty( "java.home" ), "bin", "java" )
				: join( javaHome, "bin", "java" );

		for (int i = 0; i < count; i++){
			WorkerHandle handle = new WorkerHandle( java, classpath, workerIndexLabel( i ) );

			//nothing to close here: handle.dataOut is a buffer over this process's stdin pipe and
			//stays open for the life of the worker. Closing the raw stream first left the buffered
			//writer writing into a closed pipe, so the handshake below failed immediately.
			DataOutputStream out = handle.dataOut;
			out.writeInt( 1 );              //MSG_HELLO
			out.writeInt( Worker.PROTOCOL_VERSION );
			writeParams( out );
			out.flush();

			DataInputStream reply = handle.dataIn;
			if (reply.readInt() != 1 || reply.readInt() != Worker.PROTOCOL_VERSION){
				throw new IOException( "worker " + i + " protocol mismatch" );
			}
			reply.readInt(); //MSG_PARAMS ack

			workers.add( handle );
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   started " + workers.size()
				+ " worker processes" );
	}

	private String workerIndexLabel( int i ){
		return "worker" + i;
	}

	private static String join( String... parts ){
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++){
			if (i > 0) sb.append( File.separatorChar );
			sb.append( parts[ i ] );
		}
		return sb.toString();
	}

	private void writeParams( DataOutputStream out ) throws IOException {
		out.writeInt( 2 ); //MSG_PARAMS
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
		out.writeLong( rng.nextLong() );
		Worker.writeWeights( out, ppo.network );
	}

	// --------------------------------------------------------------------------- training

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
		startWatchdog( stallTimeoutMs );

		System.out.println( Ansi.wrap( "machine", Ansi.DIM ) + "   "
				+ ResourceStats.availableProcessors() + " logical processors, pool of "
				+ workers.size() + " workers, stall timeout "
				+ ( stallTimeoutMs / 1000 ) + "s" );

		for (int g = 0; g < generations; g++){
			epoch = g;
			advanceSchedule();

			//every (seed, hero, wantReplay) is decided here, on this thread, before any worker is
			//asked. The seed pool and the RNG are shared mutable state, so handing work out first
			//and dispatching second is what keeps the concurrent dispatch below race-free while still
			//producing exactly the same assignment the sequential version did.
			episodesPerWorker = workerCount;
			List<Job> jobs = planGeneration( g, workerCount );

			ResourceStats.Interval gen = ResourceStats.start();
			List<Episode> episodes = dispatch( jobs );
			gen.stop();
			lastWorkerSeconds = sumWorkerCpuSeconds( episodes );
		
			report( g, episodes, gen );

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
	private List<Job> planGeneration( int generation, int workerCount ){
		List<Job> jobs = new ArrayList<>();

		for (int w = 0; w < workers.size(); w++){
			WorkerHandle worker = workers.get( w );
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
			}, "dispatch-" + workerIndexLabel( workers.indexOf( worker ) ) );

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
			out.writeInt( 3 ); //MSG_EPISODE
			out.writeUTF( job.seed );
			out.writeUTF( job.heroClass.name() );
			out.writeBoolean( job.wantReplay );
			out.flush();

			Episode episode = new Episode();
			episode.seed = job.seed;
			episode.heroClass = job.heroClass.name();
			episode.workerName = worker.name;

in.readInt(); //MSG_EPISODE
			episode.score = in.readDouble();
			episode.depth = in.readInt();
			episode.turns = in.readInt();
			episode.endedNaturally = in.readBoolean();
			episode.reason = in.readUTF();

			//cumulative process CPU seconds from the worker, diffed against its previous report
			episode.workerCpuTotal = in.readDouble();
			episode.workerHeapMb = in.readDouble();

			int steps = in.readInt();
			//the worker sends the step count always but the body only when a replay was requested,
			//so the body must only be read in that case. Reading it unconditionally deadlocked the
			//trainer against a worker that was already waiting for its next command.
			if (job.wantReplay) episode.replay = readReplay( in, steps );

			//only after the whole frame has been consumed, so the watchdog sees progress rather
			//than a thread that has merely started reading
			lastProgressNanos = System.nanoTime();

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
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
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

		for (final WorkerHandle worker : workers){
			Thread thread = new Thread( () -> {
				try {
					worker.dataOut.write( payload );
					worker.dataOut.flush();
					int reply = worker.dataIn.readInt();
					if (reply != Worker.MSG_PARAMS){
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

		lastProgressNanos = System.nanoTime();
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

	private final List<Episode> lastEpisodes = new ArrayList<>();

	/** Wall time of the PPO update that ran after the previous generation, for the time split. */
	private ResourceStats.Interval lastUpdate;

	/**
	 * Summed CPU seconds the workers burned during the previous generation.
	 *
	 * Reported rather than measured locally because the trainer's own cores are almost idle while
	 * the workers run, so its process CPU time says nothing about how busy the machine is.
	 */
	private double lastWorkerSeconds;

	/** Episodes each worker was asked for, for the usage line. */
	private int episodesPerWorker;

	/** Bumped by dispatch threads whenever a worker message completes. Watched by the watchdog. */
	private volatile long lastProgressNanos = System.nanoTime();

	/** Quiet period before the watchdog declares a stall. Configurable from the command line. */
	private long stallTimeoutMs = 180_000;

	/** Seconds the last generation spent on the update and the weight push, where no worker runs. */
	private double lastBarrierSeconds;

	private static final int BOSS_DEPTH = 5;
	private static final int SEED_POOL_SIZE = 100;

	// --------------------------------------------------------------------------- reporting

	private void report( int generation, List<Episode> episodes, ResourceStats.Interval gen ){
		lastEpisodes.clear();
		lastEpisodes.addAll( episodes );

		if (episodes.isEmpty()) return;

		double meanScore = 0, meanDepth = 0, meanTurns = 0;
		long totalTurns = 0;
		int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;
		Episode bestEpisode = null;

		for (Episode e : episodes){
			meanScore += e.score;
			meanDepth += e.depth;
			meanTurns += e.turns;
			totalTurns += e.turns;
			best = Math.max( best, (int) e.score );
			worst = Math.min( worst, (int) e.score );
			if (bestEpisode == null || e.score > bestEpisode.score) bestEpisode = e;
			if (e.replay != null) keepBest( e );
		}

		int n = episodes.size();
		meanScore /= n;
		meanDepth /= n;
		meanTurns /= n;

		ResourceStats.Interval ppoTime = lastUpdate;
		double ppoSeconds = ppoTime == null ? 0 : ppoTime.wallSeconds();

		System.out.println();
		System.out.println( Ansi.wrap( "generation " + generation, Ansi.BOLD + Ansi.CYAN )
				+ "  episodes=" + n
				+ "  seeds=" + seeds.activeSeeds() + "/" + seeds.totalSeeds()
				+ "  shaping=" + String.format( "%.2f", curriculumScale() ) );
		System.out.println( "  score   mean=" + Ansi.signed( meanScore )
				+ "  best=" + Ansi.signed( best )
				+ "  worst=" + Ansi.signed( worst ) );
		System.out.println( "  depth   mean=" + String.format( "%.1f", meanDepth )
				+ "  best=" + bestEpisode.depth );
		System.out.println( "  turns   mean=" + String.format( "%.0f", meanTurns )
				+ "  total=" + String.format( "%,d", totalTurns ) );
		System.out.println( "  ppo     policy=" + String.format( "%.4f", ppo.lastPolicyLoss )
				+ "  value=" + String.format( "%.4f", ppo.lastValueLoss )
				+ "  entropy=" + String.format( "%.3f", ppo.lastEntropy )
				+ "  clip=" + String.format( "%.2f", ppo.lastClipFraction )
				+ "  kl=" + String.format( "%.4f", ppo.lastKLDivergence ) );

double wall = gen.wallSeconds();

		double systemLoad = ResourceStats.systemCpuLoad();
		int logical = ResourceStats.availableProcessors();

		//workerSeconds is a summed CPU total across every episode and every worker. It is kept as a
		//cross-check on the system figure rather than used to derive one, because differencing a
		//coarse per-process counter across short episodes loses about half the signal.
		double workerSeconds = lastWorkerSeconds;
		double workerCores = wall <= 0 ? 0 : workerSeconds / wall;
		double trainerCores = Math.max( 0, gen.coresUsed() );
		double machineCores = systemLoad >= 0 ? systemLoad * logical : workerCores + trainerCores;

		System.out.println();
		System.out.println( "  speed   " + String.format( "%,.0f turns/s", ResourceStats.stepsPerSecond( totalTurns, wall ) )
				+ "  " + String.format( "%,.1f episodes/s", n / Math.max( 1e-9, wall ) )
				+ "  " + String.format( "%.2fs wall", wall )
				+ ( ppoSeconds > 0 ? "  (ppo update " + String.format( "%.2fs", ppoSeconds ) + ")" : "" )
				+ "  " + String.format( "barrier %.0f%%", pct( lastBarrierSeconds, wall ) ) );
		System.out.println( "  usage   " + ( systemLoad >= 0
						? String.format( "%.0f%% of machine (%.1f of %d logical cores)",
								systemLoad * 100, machineCores, logical )
						: String.format( "%.1f cores busy (worker-reported)", machineCores ) )
				+ "  pool " + workers.size() + " x " + episodesPerWorker + " episodes"
				+ String.format( "  workers %.1f cores, trainer %.2f", workerCores, trainerCores ) );
	}

	private static double pct( double used, double total ){
		return total <= 0 ? 0 : used / total * 100;
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

	public void shutdown(){
		for (WorkerHandle worker : workers){
			try {
				worker.process.destroy();
			} catch (RuntimeException e){
				//a worker that already exited is fine
			}
		}
		workers.clear();
	}

	// --------------------------------------------------------------------------- plumbing

	private static class Episode {
String seed = "";
		String heroClass = "WARRIOR";
		double score;
		int depth;
		int turns;
		boolean endedNaturally;
		String reason = "";
		Replay replay;

		/** Cumulative CPU seconds a worker reported, or -1 if it could not measure. */
		double workerCpuTotal = -1;

		/** Heap the worker was holding at the end of the episode, in MB. */
		double workerHeapMb = -1;

		/** Which worker ran it, so the CPU delta can be attributed per worker. */
		String workerName = "";
	}

	/**
	 * One worker process and the pipes to it.
	 *
	 * The worker reads its commands from stdin and writes its results to stdout. Worker stderr is
	 * inherited so its warnings land in the trainer's log.
	 */
	private static class WorkerHandle {
		final Process process;
		final DataInputStream dataIn;
		final DataOutputStream dataOut;
		final String name;

		WorkerHandle( String java, String classpath, String name ) throws IOException {
			this.name = name;

			ProcessBuilder pb = new ProcessBuilder(
					java,
					"-Xms256m", "-Xmx1536m",
					"-XX:+UseParallelGC", "-XX:MaxGCPauseMillis=200",
					"-cp", classpath,
					"com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerMain",
					"--worker",
					//its own save directory, so workers cannot collide on one temp file
					"--work-dir", new File( workerDir(), name ).getAbsolutePath() );

pb.redirectError( ProcessBuilder.Redirect.INHERIT );

		//ProcessBuilder resolves its working directory before the process starts, so the worker
		//cannot create its own - it fails with "The directory name is invalid" instead
		File dir = new File( workerDir(), name );
		if (!dir.isDirectory() && !dir.mkdirs()){
			throw new IOException( "could not create worker directory " + dir );
		}
		pb.directory( dir );

			this.process = pb.start();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( process.getOutputStream() ) );
			this.dataIn = new DataInputStream( new BufferedInputStream( process.getInputStream() ) );
		}
	}

	/**
	 * Fails the run if a worker goes quiet for too long.
	 *
	 * A desynchronised protocol does not crash: the trainer blocks reading a pipe the worker will
	 * never write to, both processes sit at zero CPU, and the run looks like it is still working.
	 * That is the worst possible failure mode for a job that is meant to take hours, so a stalled
	 * worker is turned into a loud error with a stack dump rather than silence.
	 *
	 * This is the one timer in the trainer. It costs nothing - it checks a volatile long once a
	 * second - and it exists precisely so that nothing else has to.
	 */
	private Thread startWatchdog( long timeoutMs ){
		Thread watchdog = new Thread( () -> {
			while (true){
				try {
					Thread.sleep( 1000 );
				} catch (InterruptedException e){
					return;
				}

				long quietMs = (System.nanoTime() - lastProgressNanos) / 1_000_000;
				if (quietMs > timeoutMs){
					System.err.println( "[ERROR] no worker progress for " + (quietMs / 1000)
							+ "s. A worker and the trainer are most likely waiting on each"
							+ " other over a desynchronised protocol." );
					for (WorkerHandle worker : workers){
						System.err.println( "  worker pid " + worker.process.pid()
								+ " alive=" + worker.process.isAlive() );
					}
					//a thread dump of the trainer is the only useful diagnostic here, and the
					//blocked frames are exactly where the protocol went wrong
					for (Thread t : Thread.getAllStackTraces().keySet()){
						if (t.getName().startsWith( "dispatch-" )){
							System.err.println( "  " + t.getName() + " " + t.getState() );
							for (StackTraceElement el : t.getStackTrace()){
								System.err.println( "      at " + el );
							}
						}
					}
					Runtime.getRuntime().halt( 3 );
				}
			}
		}, "watchdog" );
		watchdog.setDaemon( true );
		watchdog.start();
		return watchdog;
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

	private final Map<String, Double> lastCpuByWorker = new HashMap<>();

/** Parsed command line for the trainer. */
	static class Options {
		/**
		 * Defaults to one worker per logical processor.
		 *
		 * Each worker simulates on a single thread, so leaving a core unassigned is leaving
		 * throughput on the floor - and the previous fixed default of 4 left 16 of 20 cores idle
		 * on a 20-thread machine. Capped by {@link #ramForWorkerCount} so a large pool cannot
		 * page: the machine's pagefile is only 2GB, and thrashing is far slower than leaving a
		 * core unused.
		 */
		int workers = 0;

		/**
		 * Episodes each worker runs per generation.
		 *
		 * Distinct from the pool size on purpose. A generation ends with an update and a policy
		 * push, and no worker can run during either - the push alone moves ~14MB per worker. With
		 * the two numbers tied together that barrier was 45% of wall time at four workers. More
		 * episodes per worker amortises it, at the cost of coarser policy updates.
		 */
		int episodes = 0;

		int generations = 100;
		long seed = 12345L;
		String javaHome = "";
		String classpath = System.getProperty( "java.class.path" );
		File workDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-train" );
		long stallSeconds = 180;

		static Options parse( String[] args ){
			Options o = new Options();
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
						//how long a worker may be silent before the run is failed as a protocol
						//desync. A blocked pipe costs no CPU, so without this a stall is
						//indistinguishable from a long episode.
						o.stallSeconds = Long.parseLong( args[ ++i ] ); break;
					default:
						if (args[ i ].startsWith( "--" )){
							System.err.println( "[WARN] unknown option: " + args[ i ] );
						}
				}
			}
			o.workers = o.workers > 0 ? o.workers : defaultWorkerCount();
			o.episodes = o.episodes > 0 ? o.episodes : o.workers;
			return o;
		}

		/**
		 * One worker per logical processor, trimmed to what memory allows.
		 *
		 * A worker holds at most a capped rollout buffer (98MB at the default 2048) plus its own
		 * engine overhead, so ~1.2GB per worker is a safe budget with room for the trainer itself.
		 */
		static int defaultWorkerCount(){
			int cores = ResourceStats.availableProcessors();
			long budget = System.getProperty( "os.name" ) == null
					? Long.MAX_VALUE
					: availableMemoryBytes() / (1200L * 1024 * 1024);
			int byMemory = (int) Math.max( 1, Math.min( cores, budget ) );
			return byMemory;
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
}
