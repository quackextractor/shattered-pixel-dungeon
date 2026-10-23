package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.AutoTune;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.fail;

public final class ConfigAutoTuneCheck {

	private ConfigAutoTuneCheck(){}

	public static void checkMemoryIsWhatLinuxCanReclaim(){
		long bytes = AutoTune.memAvailable( List.of(
				"MemTotal:       15738200 kB",
				"MemFree:          226720 kB",
				"MemAvailable:   14239816 kB" ) );
		if (bytes != 14239816L * 1024){
			fail( "autotune read " + bytes + " bytes from /proc/meminfo, expected MemAvailable's "
					+ 14239816L * 1024 + ". MemFree leaves out page cache, so an idle Linux box with 13 GB"
					+ " reclaimable sized the pool to one worker." );
		}
	}

	public static void checkThePoolFitsCoresAndMemory(){
		long worker = AutoTune.megabytes( "1536m" ) + 300;
		int roomy = AutoTune.workersFor( 4, 13900, 1836, worker );
		int tight = AutoTune.workersFor( 20, 6000, 1836, worker );
		int starved = AutoTune.workersFor( 8, 1000, 1836, worker );
		if (roomy != 4 || tight != 1 || starved != 1 || AutoTune.megabytes( "2g" ) != 2048){
			fail( "autotune sized pools " + roomy + "/" + tight + "/" + starved
					+ ", expected 4 (core-bound), 1 (memory-bound) and 1 (never zero)" );
		}
	}

	public static void checkTheFewestThreadsNearTheBestWin(){
		int pick = AutoTune.pickThreads( new double[]{ 100, 52, 30, 29.5 } );
		int flat = AutoTune.pickThreads( new double[]{ 10, 10.2, 10.1 } );
		if (pick != 2 || flat != 0){
			fail( "autotune picked thread index " + pick + " and " + flat + ", expected 2 and 0: within 5%"
					+ " of the best, fewer threads leave cores to the collection running beside the update" );
		}
	}

	public static void checkTheProbeGivesEveryThreadAShard(){
		List< Integer > wide = AutoTune.threadCounts( 20, 8 );
		List< Integer > narrow = AutoTune.threadCounts( 4, 8 );
		int chunks = AutoTune.probeSamples( 8, 16 ) / 16;
		int capped = AutoTune.probeSamples( 32, 64 );
		if (!wide.equals( List.of( 1, 2, 4, 8 ) ) || !narrow.equals( List.of( 1, 2, 4 ) ) || chunks < 8 * 2
				|| capped != 2 * 32 * 64){
			fail( "autotune probed threads " + wide + " and " + narrow + " over " + chunks + " chunks and " + capped
					+ " samples at minibatch 32, chunk 64, expected"
					+ " [1, 2, 4, 8] and [1, 2, 4] over at least two minibatches of 8 chunks, and two minibatches"
					+ " once four pass the sample cap. An update shards"
					+ " one minibatch's chunks across threads, so a 4-chunk probe timed 4 and 16 threads alike"
					+ " and left a 20-core machine at 4 where 8 is 29% faster" );
		}
	}

	public static void checkTheProbeCutsRaggedChunks(){
		List< Transition > samples = new ArrayList<>();
		for (int i = 0; i < 512; i++) samples.add( new Transition() );
		AutoTune.cutProbeChunks( samples, 16 );
		int chunks = 0, shortChunks = 0, longest = 0, run = 0;
		for (int i = 0; i < samples.size(); i++){
			Transition t = samples.get( i );
			boolean last = i + 1 == samples.size() || samples.get( i + 1 ).episodeId != t.episodeId;
			run = t.stepIndex == 0 ? 1 : run + 1;
			if (!last) continue;
			chunks++;
			if (run < 16) shortChunks++;
			longest = Math.max( longest, run );
		}
		if (longest > 16 || shortChunks * 10 < chunks || shortChunks * 10 > chunks * 4){
			fail( "autotune cut 512 probe samples into " + chunks + " chunks, " + shortChunks + " short, longest "
					+ longest + "; expected none past 16 and 10-40% short. Real chunks end at episode tails, so a"
					+ " probe of full chunks overstates how evenly an update shards" );
		}
	}

	public static void checkAnExplicitFlagBeatsTheProbe() throws IOException {
		TrainOptions o = TrainOptions.parse( new String[]{ "--auto", "--workers", "3", "--hours", "5.5" } );
		o.apply( AutoTune.fixed( 7, 5 ) );
		if (!o.auto || o.hours != 5.5 || o.workers != 3 || o.episodes != 3 || o.updateThreads != 5){
			fail( "--auto with --workers 3 gave workers " + o.workers + ", episodes " + o.episodes
					+ ", update threads " + o.updateThreads + ", hours " + o.hours + "; expected 3, 3, 5, 5.5" );
		}
	}
}
