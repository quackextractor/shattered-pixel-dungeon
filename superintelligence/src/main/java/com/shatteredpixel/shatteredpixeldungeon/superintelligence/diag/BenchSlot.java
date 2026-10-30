package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.RemoteNetwork;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.InferenceBroker;

import java.io.File;
import java.util.Random;

public final class BenchSlot {

	private static SPDEnv env;
	private static EpisodeCollector collector;

	private BenchSlot(){}

	public static void init( String dir ){
		HeadlessServices.install( new File( dir ));
		HeadlessServices.disableSaving( true );
		EnvConfig config = new EnvConfig();
		env = new SPDEnv( config, HeadlessGame.install() );
		Random rng = new Random( 77 );
		collector = new EpisodeCollector( config, new RemoteNetwork( config, rng, InferenceBroker.server(), -1 ),
				rng, new Random( 5 ), 0.99f, 0.95f, 0.25f, 2048, 16 );
		collector.tailSteps( 20 );
		collector.teacher( new DescentBot() );
	}

	public static long run( String seed, int hero ){
		collector.run( env, seed, HeroClass.values()[ hero ], 4242 );
		return env.turnsTotal();
	}
}
