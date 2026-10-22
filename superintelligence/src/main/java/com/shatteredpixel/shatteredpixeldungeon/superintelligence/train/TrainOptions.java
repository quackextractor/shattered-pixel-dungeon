package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.File;
import java.io.IOException;


public class TrainOptions {


	public int workers = 0;


	public int episodes = 0;

	public int generations = 100;
	public long seed = 12345L;


	public int epochs = 4;


	public int minibatchSize = 8;


	public int updateThreads = 8;


	public double sampleRate = 0.25;


	public int maxSampledPerEpisode = 2048;


	public int maxSamplesPerGeneration = 8192;

	public String javaHome = "";
	public String classpath = System.getProperty( "java.class.path" );


	public java.nio.file.Path configFile;
	public File workDir = Trainer.outDirDefault();


	public File replayDir = new File( Trainer.replayDirDefault(), "trainer-replays" );


	public String replayGroup = "";


	public File demoDir;


	public int recordEvery = 1;


	public static boolean validGroup( String group ){
		if (group == null || group.isEmpty()) return true;
		if (group.length() > 64) return false;
		if (group.equals( "." ) || group.equals( ".." )) return false;
		for (int i = 0; i < group.length(); i++){
			char c = group.charAt( i );
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
			if (!ok) return false;
		}
		return true;
	}

	public long stallSeconds = 180;


	public File metricsCsv;
	public String traceWorld = null;
	public java.io.File rngTraceDir = null;


	public File save;


	public int checkpointEvery = 25;


	public File resume;


	public PpoHyperparameters hyper;


	private boolean metricsGiven, saveGiven, workersGiven, episodesGiven, updateThreadsGiven;


	public boolean auto;


	public double hours;


	private String rewardProfile;


	public String rewardProfile(){ return rewardProfile; }

	public TrainOptions(){
		metricsCsv = Trainer.evidenceDirDefault( "metrics.csv" );
		save = new File( workDir, "weights.bin" );
	}


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

		o.epochs = hyper.epochs;
		o.minibatchSize = hyper.minibatchSize;
		o.updateThreads = hyper.updateThreads;
		o.sampleRate = hyper.sampleRate;
		o.maxSampledPerEpisode = hyper.maxSampledPerEpisode;
		o.maxSamplesPerGeneration = hyper.maxSamplesPerGeneration;
		o.stallSeconds = hyper.stallSeconds;
		o.checkpointEvery = hyper.checkpointEvery;
		o.recordEvery = hyper.recordEvery;

		for (int i = 0; i < args.length; i++){

			final int at = i;

			switch (args[ i ]) {
				case "--config":     i++; break;
				case "--reward-profile":
					o.rewardProfile = value( args, at ); i++; break;
				case "--workers":     o.workers = Integer.parseInt( value( args, at )); o.workersGiven = true; i++; break;
				case "--auto":        o.auto = true; break;
				case "--hours":       o.hours = Double.parseDouble( value( args, at )); i++; break;
				case "--generations": o.generations = Integer.parseInt( value( args, at )); i++; break;
				case "--episodes":   o.episodes = Integer.parseInt( value( args, at )); o.episodesGiven = true; i++; break;
				case "--seed":        o.seed = Long.parseLong( value( args, at )); i++; break;
				case "--java-home":   o.javaHome = value( args, at ); i++; break;
				case "--classpath":   o.classpath = value( args, at ); i++; break;
				case "--out":         o.workDir = resolve( value( args, at )); i++; break;
				case "--replay-dir":   o.replayDir = resolve( value( args, at )); i++; break;
				case "--replay-group": o.replayGroup = value( args, at ); i++; break;
				case "--demos":       o.demoDir = resolve( value( args, at )); i++; break;
				case "--record-every": o.recordEvery = Integer.parseInt( value( args, at )); i++; break;
				case "--stall-seconds":

					o.stallSeconds = Long.parseLong( value( args, at )); i++; break;
				case "--epochs":      o.epochs = Integer.parseInt( value( args, at )); i++; break;
				case "--minibatch":   o.minibatchSize = Integer.parseInt( value( args, at )); i++; break;
				case "--update-threads": o.updateThreads = Integer.parseInt( value( args, at )); o.updateThreadsGiven = true; i++; break;

				case "--sample-rate": o.sampleRate = Double.parseDouble( value( args, at )); i++; break;
				case "--max-sampled-per-episode":
					o.maxSampledPerEpisode = Integer.parseInt( value( args, at )); i++; break;
				case "--max-samples":
					o.maxSamplesPerGeneration = Integer.parseInt( value( args, at )); i++; break;
				case "--metrics":     o.metricsCsv = resolve( value( args, at )); o.metricsGiven = true; i++; break;
				case "--trace-world":

					o.traceWorld = value( args, at ); i++; break;
				case "--rng-trace-dir":

					o.rngTraceDir = resolve( value( args, at )); i++; break;
				case "--save":        o.save = resolve( value( args, at )); o.saveGiven = true; i++; break;
				case "--resume":      o.resume = resolve( value( args, at )); i++; break;
				case "--checkpoint-every":
					o.checkpointEvery = Integer.parseInt( value( args, at )); i++; break;
				default:
					if (args[ i ].startsWith( "--" )){
						System.err.println( "[WARN] unknown option: " + args[ i ] );
					}
			}
		}
		o.workers = o.workers > 0 ? o.workers : defaultWorkerCount();
		o.episodes = o.episodes > 0 ? o.episodes : o.workers;
		if (o.checkpointEvery < 1) o.checkpointEvery = 1;
		if (o.recordEvery < 1) o.recordEvery = 1;

		if (o.minibatchSize < 1) o.minibatchSize = 1;
		if (o.epochs < 1) o.epochs = 1;
		if (o.updateThreads < 0) o.updateThreads = 0;
		if (o.maxSampledPerEpisode < 1) o.maxSampledPerEpisode = 1;
		if (o.maxSamplesPerGeneration < 1) o.maxSamplesPerGeneration = 1;

		o.deriveOutputsUnder( o.workDir );

		if (o.rewardProfile != null){
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
					.parse( o.rewardProfile );
		}
		if (!validGroup( o.replayGroup )){
			throw new IllegalArgumentException( "not a usable --replay-group: '" + o.replayGroup
					+ "'. Letters, digits, '.', '_' and '-' only, up to 64, because a group names a"
					+ " directory under the replay directory and a section of the catalogue." );
		}
		return o;
	}


	public void apply( AutoTune tune ){
		if (!workersGiven) workers = tune.workers;
		if (!episodesGiven) episodes = workers;
		if (!updateThreadsGiven) updateThreads = tune.updateThreads;
	}


	private void deriveOutputsUnder( File dir ){
		if (!metricsGiven) metricsCsv = new File( dir, "metrics.csv" );
		if (!saveGiven) save = new File( dir, "weights.bin" );
	}


	private static String value( String[] args, int at ){
		if (at + 1 >= args.length){
			throw new IllegalArgumentException( args[ at ] + " needs a value" );
		}
		return args[ at + 1 ];
	}


	static int defaultWorkerCount(){
		int cores = ResourceStats.availableProcessors();
		long budget = System.getProperty( "os.name" ) == null
				? Long.MAX_VALUE
				: AutoTune.availableMemoryBytes() / (1200L * 1024 * 1024);
		return (int) Math.max( 1, Math.min( cores, budget ) );
	}


	static File resolve( String path ){
		return com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions
				.resolve( new File( path ));
	}


	static long availableMemoryBytes(){
		try {
			java.lang.management.OperatingSystemMXBean os =
					java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			java.lang.reflect.Method m = Class.forName( "com.sun.management.OperatingSystemMXBean" )
					.getMethod( "getFreeMemorySize" );
			Object v = m.invoke( os );
			if (v instanceof Number) return ((Number) v).longValue();
		} catch (ReflectiveOperationException | RuntimeException ignored){

		}
		return Long.MAX_VALUE;
	}
}
