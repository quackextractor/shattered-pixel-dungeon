package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Random;

final class RunCheckpoints {

	private final EnvConfig config;
	private final PPO ppo;
	private final SeedPool seeds;
	private final Random rng;
	private final long rngSeed;

	private File saveFile;
	private int every = 25;
	private int lastSaved = -1;
	private int completed;

	RunCheckpoints( EnvConfig config, PPO ppo, SeedPool seeds, Random rng, long rngSeed ){
		this.config = config;
		this.ppo = ppo;
		this.seeds = seeds;
		this.rng = rng;
		this.rngSeed = rngSeed;
	}

	void saveTo( File saveFile, int everyGenerations ){
		this.saveFile = saveFile;
		this.every = Math.max( 1, everyGenerations );
	}

	Checkpoint.Meta resume( File file ) throws IOException {
		if (!file.isFile()){
			throw new FileNotFoundException( "--resume " + file
					+ " does not exist. Pass a file written by a previous run, or omit the flag to"
					+ " start from a fresh random policy." );
		}

		Checkpoint.Meta meta = Checkpoint.load( file, ppo.network, config );
		lastSaved = meta.generation;
		completed = meta.generation;

		seeds.resume( meta.seedPoolSeed, meta.seedScheduleSeed, meta.seedDraws, meta.seedPoolSize,
				meta.activeSeeds );

		if (meta.hasTrainerState){
			ppo.valueNorm.restore( meta.valueMean, meta.valueStd, meta.valueReady );
			rng.setSeed( meta.rngState );
		}
		return meta;
	}

	void completed( int generation, int lastGeneration ){
		completed = generation;
		if (saveFile == null) return;
		if (generation % every != 0 && generation != lastGeneration) return;
		write( generation );
	}

	void saveOnExit(){
		if (saveFile == null) return;
		if (lastSaved >= completed) return;
		write( completed );
	}

	void write( int generation ){
		if (saveFile == null) return;

		long rngState = rng.nextLong();
		rng.setSeed( rngState );
		try {
			Checkpoint.save( saveFile, ppo.network, config, generation, rngSeed,
					seeds.poolSeed(), seeds.scheduleSeed(), seeds.draws(), seeds.poolSize(),
					seeds.activeSeeds(), ppo.valueNorm.mean(), ppo.valueNorm.std(), ppo.valueNorm.ready(),
					rngState );
			lastSaved = generation;
			System.out.println( Ansi.wrap( "saved", Ansi.DIM ) + "   " + Trainer.describe( saveFile )
					+ "  " + Ansi.wrap( "(generation " + generation + ", adam step "
					+ ppo.network.adamSteps() + ")", Ansi.DIM ) );
		} catch (IOException e){
			System.err.println( "[ERROR] could not write " + saveFile + ": " + e.getMessage() );
			System.err.println( "        This run's policy will be lost. Nothing else is affected." );
		}
	}
}
