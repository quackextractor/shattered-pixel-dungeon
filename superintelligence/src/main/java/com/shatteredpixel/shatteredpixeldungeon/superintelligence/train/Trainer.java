package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
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
import java.util.HashMap;
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
			trainer.launchWorkers( options.workers, options.javaHome, options.classpath );
			trainer.train( options.generations, options.workers );
		} catch (IOException e){
			System.err.println( "[ERROR] training failed: " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
		} finally {
			trainer.shutdown();
		}
	}

	// --------------------------------------------------------------------------- workers

	/** Starts {@code count} worker JVMs and hands each the current policy. */
	public void launchWorkers( int count, String javaHome, String classpath ) throws IOException {
		String java = (javaHome == null || javaHome.isEmpty())
				? join( System.getProperty( "java.home" ), "bin", "java" )
				: join( javaHome, "bin", "java" );

		for (int i = 0; i < count; i++){
			WorkerHandle handle = new WorkerHandle( java, classpath, workerIndex( i ) );
			handle.process.getOutputStream().close(); //the worker writes to a pipe, not a file

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

	private String workerIndex( int i ){
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

		for (int g = 0; g < generations; g++){
			epoch = g;
			advanceSchedule();

			long start = System.nanoTime();
			List<Episode> episodes = new ArrayList<>();

			for (WorkerHandle worker : workers){
				episodes.addAll( runOn( worker, workerCount, g ) );
			}

			double elapsedMs = (System.nanoTime() - start) / 1e6;
			report( g, episodes, elapsedMs );

			if (!episodes.isEmpty()){
				ppo.update();
				pushWeights();
			}
		}

		saveBestReplays();
	}

	/** Asks one worker for {@code count} episodes. */
	private List<Episode> runOn( WorkerHandle worker, int count, int generation ) throws IOException {
		List<Episode> episodes = new ArrayList<>();

		DataOutputStream out = worker.dataOut;
		DataInputStream in = worker.dataIn;

		for (int i = 0; i < count; i++){
			//every third seed is one worth keeping a replay of
			boolean wantReplay = (i % 3 == 0);

			String seed = seeds.next();
			HeroClass heroClass = HeroClass.values()[ rng.nextInt( HeroClass.values().length ) ];

			out.writeInt( 3 ); //MSG_EPISODE
			out.writeUTF( seed );
			out.writeUTF( heroClass.name() );
			out.writeBoolean( wantReplay );
			out.flush();

			Episode episode = new Episode();
			episode.seed = seed;
			episode.heroClass = heroClass.name();

			in.readInt(); //MSG_EPISODE
			episode.score = in.readDouble();
			episode.depth = in.readInt();
			episode.turns = in.readInt();
			episode.endedNaturally = in.readBoolean();
			episode.reason = in.readUTF();

			int steps = in.readInt();
			if (wantReplay) episode.replay = readReplay( in, steps );
			else skipReplay( in, steps );

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

	private void skipReplay( DataInputStream in, int steps ) throws IOException {
		in.readUTF();
		in.readUTF();
		in.readDouble();
		in.readInt();
		in.readInt();
		for (int i = 0; i < steps; i++){
			in.readUTF();
			in.readInt();
			in.readUTF();
			in.readInt();
		}
	}

	/** Pushes the updated policy to every worker. */
	private void pushWeights() throws IOException {
		byte[] payload = new byte[ 0 ];
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		DataOutputStream tmp = new DataOutputStream( buffer );
		Worker.writeWeights( tmp, ppo.network );
		tmp.flush();
		payload = buffer.toByteArray();

		for (WorkerHandle worker : workers){
			worker.process.getOutputStream().write( payload );
			worker.process.getOutputStream().flush();
		}
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

	private static final int BOSS_DEPTH = 5;
	private static final int SEED_POOL_SIZE = 100;

	// --------------------------------------------------------------------------- reporting

	private void report( int generation, List<Episode> episodes, double elapsedMs ){
		lastEpisodes.clear();
		lastEpisodes.addAll( episodes );

		if (episodes.isEmpty()) return;

		double meanScore = 0, meanDepth = 0, meanTurns = 0;
		int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;
		Episode bestEpisode = null;

		for (Episode e : episodes){
			meanScore += e.score;
			meanDepth += e.depth;
			meanTurns += e.turns;
			best = Math.max( best, (int) e.score );
			worst = Math.min( worst, (int) e.score );
			if (bestEpisode == null || e.score > bestEpisode.score) bestEpisode = e;
			if (e.replay != null) keepBest( e );
		}

		int n = episodes.size();
		meanScore /= n;
		meanDepth /= n;
		meanTurns /= n;

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
				+ String.format( "  %.0f episodes/s", n * 1000.0 / Math.max( 1.0, elapsedMs ) ) );
		System.out.println( "  ppo     policy=" + String.format( "%.4f", ppo.lastPolicyLoss )
				+ "  value=" + String.format( "%.4f", ppo.lastValueLoss )
				+ "  entropy=" + String.format( "%.3f", ppo.lastEntropy )
				+ "  clip=" + String.format( "%.2f", ppo.lastClipFraction )
				+ "  kl=" + String.format( "%.4f", ppo.lastKLDivergence ) );
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
					"--worker" );

			pb.redirectError( ProcessBuilder.Redirect.INHERIT );
			pb.directory( new File( System.getProperty( "java.io.tmpdir" ), "spd-worker" ) );

			this.process = pb.start();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( process.getOutputStream() ) );
			this.dataIn = new DataInputStream( new BufferedInputStream( process.getInputStream() ) );
		}
	}

	/** Parsed command line for the trainer. */
	static class Options {
		int workers = 4;
		int generations = 100;
		long seed = 12345L;
		String javaHome = "";
		String classpath = System.getProperty( "java.class.path" );
		File workDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-train" );

		static Options parse( String[] args ){
			Options o = new Options();
			for (int i = 0; i < args.length; i++){
				switch (args[ i ]) {
					case "--workers":     o.workers = Integer.parseInt( args[ ++i ] ); break;
					case "--generations": o.generations = Integer.parseInt( args[ ++i ] ); break;
					case "--seed":        o.seed = Long.parseLong( args[ ++i ] ); break;
					case "--java-home":   o.javaHome = args[ ++i ]; break;
					case "--classpath":   o.classpath = args[ ++i ]; break;
					case "--out":         o.workDir = new File( args[ ++i ] ); break;
					default:
						if (args[ i ].startsWith( "--" )){
							System.err.println( "[WARN] unknown option: " + args[ i ] );
						}
				}
			}
			return o;
		}
	}
}