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


public class Trainer {

	private final EnvConfig config;
	private final PpoHyperparameters hyper;
	private final Random rng;
	private final SeedPool seeds;
	private final File workDir;


	private final String traceWorldPath;


	private final File replayDirOverride;


	private final String replayGroup;

	private final PPO ppo;


	final TrainerWorkers pool;

	private final ReplayKeeper keeper = new ReplayKeeper();


	private final int recordEvery;


	private int epoch = 0;


	private final List<Episode> lastEpisodes = new ArrayList<>();


	MetricsHistory metrics;


	private boolean barDrawn;


	private File saveFile;
	private int checkpointEvery = 25;


	private Checkpoint.Meta resumedFrom;


	private int lastGeneration;


	private int lastSavedGeneration = -1;


	private long rngSeed;


	private ResourceStats.Interval lastUpdate;


	private double lastWorkerSeconds;


	private int episodesPerWorker;


	private final Map<String, Double> lastCpuByWorker = new HashMap<>();


	public double sampleRate = 0.05;


	double sampleRate(){
		return Math.min( 1.0, Math.max( 0.0, sampleRate ) );
	}


	public double maxSampledPerEpisode = 2048;

	int maxSampledPerEpisode(){
		return Math.max( EpisodeCollector.TAIL_STEPS,
				Math.min( Integer.MAX_VALUE, (int) maxSampledPerEpisode ) );
	}


	public int maxSamplesPerGeneration = 8192;

	private static final int BOSS_DEPTH = 5;
	private static final int SEED_POOL_SIZE = 100;

	public Trainer( EnvConfig config, long seed, File workDir ){
		this( config, new PpoHyperparameters(), seed, workDir,
				new File( replayDirDefault(), "trainer-replays" ), null, "", 1 );
	}


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
		this.hyper = hyper;
		this.ppo = new PPO( config, rng );
		this.pool = new TrainerWorkers( config, ppo );
		WorkerPool.bufferBytes = hyper.pipeBufferBytes;
		WorkerPool.heapMin = hyper.workerHeapMin;
		WorkerPool.heapMax = hyper.workerHeapMax;
		WorkerPool.gcPauseMs = hyper.workerGcPauseMs;
		TrainerWorkers.pushChunkBytes = hyper.pushChunkBytes;
		this.seeds.randomFraction = (float) hyper.randomSeedRate;
		learning( hyper );
	}




	private static java.nio.file.Path configFileIn( String[] args ){
		for (int i = 0; i < args.length - 1; i++){
			if (args[ i ].equals( "--config" )){
				return java.nio.file.Paths.get( args[ i + 1 ] );
			}
		}
		return null;
	}


	public void batch( int epochs, int minibatchSize ){
		ppo.epochs = epochs;
		ppo.minibatchSize = minibatchSize;
	}


	public void updateThreads( int threads ){
		ppo.threads = threads;
	}


	private void learning( PpoHyperparameters hyper ){
		ppo.gamma = hyper.gamma;
		ppo.lambda = hyper.lambda;
		ppo.clipEpsilon = hyper.clipEpsilon;
		ppo.entropyCoeff = hyper.entropyCoeff;
		ppo.learningRate = hyper.learningRate;
		ppo.network.learningRate = hyper.learningRate;

		ppo.chunkLength = hyper.chunkLength;
		ppo.normaliseValues = hyper.normaliseValues;
		ppo.valueNorm.momentum = hyper.valueNormMomentum;
		ppo.valueLossCoefficient = hyper.valueLossCoefficient;
		ppo.valueGradientClip = hyper.valueGradientClip;
		ppo.huberValues = hyper.huberValues;

		ppo.network.gradClip = hyper.maxGradNorm;
		ppo.network.beta1 = hyper.adamBeta1;
		ppo.network.beta2 = hyper.adamBeta2;
		ppo.network.adamEps = hyper.adamEps;
	}


	public MetricsHistory metrics(){ return metrics; }

	public void closeUpdate(){
		ppo.close();
	}


	public void sampling( double rate, int perEpisode, int perGeneration ){
		this.sampleRate = rate;
		this.maxSampledPerEpisode = perEpisode;
		this.maxSamplesPerGeneration = perGeneration;
	}


	public void history( MetricsHistory metrics ){
		this.metrics = metrics;
	}


	public Checkpoint.Meta resume( File file ) throws IOException {
		if (file == null) return null;

		if (!file.isFile()){
			throw new FileNotFoundException( "--resume " + file
					+ " does not exist. Pass a file written by a previous run, or omit the flag to"
					+ " start from a fresh random policy." );
		}

		Checkpoint.Meta meta = Checkpoint.load( file, ppo.network, config );

		epoch = meta.generation;
		resumedFrom = meta;

		lastSavedGeneration = meta.generation;

		System.out.println( Ansi.wrap( "resume", Ansi.DIM ) + "   loaded " + file
				+ "  " + Ansi.wrap( "(" + meta + ")", Ansi.DIM ) );
		return meta;
	}


	public void checkpointing( File saveFile, int everyGenerations ){
		this.saveFile = saveFile;
		this.checkpointEvery = Math.max( 1, everyGenerations );
	}


	void writeParams( DataOutputStream out ) throws IOException {
		out.writeInt( Protocol.MSG_PARAMS );

		config.writeTo( out );

		out.writeFloat( ppo.gamma );
		out.writeFloat( ppo.lambda );

		out.writeUTF( traceWorldPath == null ? "" : traceWorldPath );

		out.writeFloat( (float) sampleRate() );
		out.writeInt( maxSampledPerEpisode() );
		out.writeInt( hyper.tailSteps );

		out.writeLong( rng.nextLong() );
		Worker.writeWeights( out, ppo.network );
	}


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


	private int droppedThisGeneration;

	private int lastSampledSteps;
	private double lastAdvantageMean;
	private double lastAdvantageStd;


	public void train( int generations, int workerCount ) throws IOException {
		seeds.fill( SEED_POOL_SIZE );
		pool.startWatchdog();

		if (metrics != null) metrics.open();

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

			episodesPerWorker = workerCount;
			List<TrainerWorkers.Job> jobs = planGeneration( workerCount );

			ResourceStats.Interval gen = ResourceStats.start();
			List<Episode> episodes = pool.dispatch( jobs );
			gen.stop();
			lastWorkerSeconds = sumWorkerCpuSeconds( episodes );

			for (Episode e : episodes) ppo.addAll( e.transitions );
			enforceGenerationCap();

			lastSampledSteps = ppo.bufferSize();
			lastAdvantageMean = ppo.meanAdvantage();
			lastAdvantageStd = ppo.advantageStdDev();

			if (!episodes.isEmpty()){
				ResourceStats.Interval update = ResourceStats.start();
				ppo.update();
				update.stop();
				lastUpdate = update;

				ResourceStats.Interval push = ResourceStats.start();
				pool.pushWeights();
				push.stop();

				lastBarrierSeconds = update.wallSeconds() + push.wallSeconds();
			}

			report( g, episodes, gen );

			maybeCheckpoint( g + 1 );

			clearProgressBar();
			drawProgressBar( g + 1 - first, generations, startedAt );
		}

		clearProgressBar();

		saveBestReplays();

		if (metrics != null) metrics.printTrend();
	}


	private void drawProgressBar( int done, int total, long startedAt ){
		double meanScore = Double.NaN;
		int bestDepth = 0;

		if (metrics != null){

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


	private void clearProgressBar(){
		if (!barDrawn) return;
		System.out.println();
		barDrawn = false;
	}


	private void maybeCheckpoint( int generation ){
		if (saveFile == null) return;
		if (generation % checkpointEvery != 0 && generation != lastGeneration) return;

		writeCheckpoint( generation );
	}


	public void saveOnExit(){
		if (saveFile == null) return;
		if (lastSavedGeneration >= lastGeneration) return;
		writeCheckpoint( lastGeneration );
	}


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


	private List<TrainerWorkers.Job> planGeneration( int workerCount ){
		List<TrainerWorkers.Job> jobs = new ArrayList<>();

		for (WorkerHandle worker : pool.workers()){
			for (int i = 0; i < workerCount; i++){

				boolean wantReplay = (i % recordEvery) == 0;
				String seed = seeds.next();
				HeroClass heroClass = HeroClass.values()[ rng.nextInt( HeroClass.values().length ) ];
				jobs.add( pool.job( worker, seed, heroClass, wantReplay ));
			}
		}

		return jobs;
	}


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


	private double lastBarrierSeconds;


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

		droppedThisGeneration = 0;

		GenerationReport.print( s );

		if (metrics != null) metrics.record( s );
	}

	private float curriculumScale(){
		return 1f;
	}


	private void keepBest( Episode episode ){
		keeper.consider( episode.replay );
	}

private void saveBestReplays(){
		clearProgressBar();
		File dir = replayDir();
		int saved = 0;

		replayDirBase().mkdirs();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIndex.record( replayDirBase() );

		for (Replay replay : keeper.keep()){
			replay.generation = epoch;

			replay.group = replayGroup;

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



	static File replayDirDefault(){
		File here = new File( "." ).getAbsoluteFile();
		for (int i = 0; i < 6 && here != null; i++){
			if (new File( here, "superintelligence.properties" ).isFile()) return tidy( here );

			if (new File( here, "superintelligence/build.gradle" ).isFile()){
				return tidy( new File( here, "superintelligence" ) );
			}
			here = here.getParentFile();
		}

		return tidy( new File( "." ).getAbsoluteFile() );
	}


	public static File outDirDefault(){
		return new File( replayDirDefault(), "out/run" );
	}


	public static File evidenceDirDefault( String name ){
		return new File( new File( replayDirDefault(), "out/evidence" ), name );
	}


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


	private File replayDir(){
		return replayGroup.isEmpty() ? replayDirBase() : new File( replayDirBase(), replayGroup );
	}


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
