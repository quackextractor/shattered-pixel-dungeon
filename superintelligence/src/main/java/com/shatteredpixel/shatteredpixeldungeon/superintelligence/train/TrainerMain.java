package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.MetricsHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TrainerMain {

	private TrainerMain() {}

	public static void main( String[] args ){
		TrainOptions options;
		EnvConfig config;
		try {

			options = TrainOptions.parse( args );
			config = loadedConfig( configFileIn( args ));
		} catch (IOException | IllegalArgumentException e){

			System.err.println( "[ERROR] " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
			return;
		}

		if (options.rewardProfile() != null){
			config.rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
					.RewardProfile.parse( options.rewardProfile() );
			System.out.println( "reward   profile " + config.rewardProfile.key()
					+ " (--reward-profile)" );
		}

Trainer trainer = new Trainer( config, options.hyper, options.seed, options.workDir,
				options.replayDir, options.traceWorld, options.replayGroup,
				options.recordEvery,
				options.rngTraceDir == null ? null : options.rngTraceDir.getAbsolutePath() );

		int exitCode = 0;
		try {
			trainer.pool.stallTimeoutMs( options.stallSeconds * 1000 );

			trainer.batch( options.epochs, options.minibatchSize );
			trainer.updateThreads( options.updateThreads );
			trainer.sampling( options.sampleRate, options.maxSampledPerEpisode,
					options.maxSamplesPerGeneration );
			trainer.history( new MetricsHistory( options.metricsCsv ));
			trainer.checkpointing( options.save, options.checkpointEvery );

			trainer.resume( options.resume );

			trainer.pool.launch( options.workers, options.javaHome, options.classpath,
					out -> trainer.writeParams( out ) );
			trainer.train( options.generations, options.episodes );
		} catch (IOException e){
			System.err.println( "[ERROR] training failed: " + e.getMessage() );

			e.printStackTrace();
			exitCode = 1;
		} finally {

			try {
				trainer.saveOnExit();
			} catch (RuntimeException ignored){

			}

			trainer.pool.shutdown();
			trainer.closeUpdate();
			if (trainer.metrics() != null) trainer.metrics().close();
		}
		if (exitCode != 0) System.exit( exitCode );
	}

	public static EnvConfig loadedConfig( Path configFile ) throws IOException {
		EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( configFile );
		System.out.println( EnvConfigBinder.describe( configFile, loaded.keysApplied ));
		return loaded.config;
	}

	private static Path configFileIn( String[] args ){
		for (int i = 0; i < args.length - 1; i++){
			if (args[ i ].equals( "--config" )){
				return Paths.get( args[ i + 1 ] );
			}
		}
		return null;
	}
}
