package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.AutoTune;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

import java.io.IOException;
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

	public static void checkAnExplicitFlagBeatsTheProbe() throws IOException {
		TrainOptions o = TrainOptions.parse( new String[]{ "--auto", "--workers", "3", "--hours", "5.5" } );
		o.apply( AutoTune.fixed( 7, 5 ) );
		if (!o.auto || o.hours != 5.5 || o.workers != 3 || o.episodes != 3 || o.updateThreads != 5){
			fail( "--auto with --workers 3 gave workers " + o.workers + ", episodes " + o.episodes
					+ ", update threads " + o.updateThreads + ", hours " + o.hours + "; expected 3, 3, 5, 5.5" );
		}
	}
}
