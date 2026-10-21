package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class DemoSource {

	private static final int FIRST_EPISODE_ID = 1_000_000_000;

	private final List< Replay > demos = new ArrayList<>();

	private final File scratch;

	private boolean installed;

	public DemoSource( File dir, File scratch, EnvConfig config ) throws IOException {
		this.scratch = scratch;
		File[] files = dir.listFiles( (d, name) -> name.endsWith( ".replay" ) || name.endsWith( ".dat" ));
		if (files == null) throw new IOException( "no demonstration directory at " + dir );
		java.util.Arrays.sort( files );
		for (File f : files){
			Replay replay = ReplayIO.read( f );
			EnvConfig recorded = ReplayIO.configFor( replay );
			if (recorded.gridWidth != config.gridWidth || recorded.gridHeight != config.gridHeight
					|| recorded.maxSlots != config.maxSlots){
				System.err.println( "[WARN] demonstration " + f.getName() + " was recorded under a different grid or slot"
						+ " count and is skipped" );
				continue;
			}
			demos.add( replay );
		}
		System.out.println( "demos    " + demos.size() + " demonstration(s) from " + dir );
	}

	public List< Transition > transitions( Network network ){
		if (!installed){
			HeadlessServices.install( scratch );
			HeadlessServices.disableSaving( true );
			installed = true;
		}
		List< Transition > out = new ArrayList<>();
		for (int k = 0; k < demos.size(); k++){
			Replay replay = demos.get( k );
			EnvConfig config = ReplayIO.configFor( replay );
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			EpisodeCollector collector = new EpisodeCollector( config, network, new Random( k ), new Random( k ), 0.99f, 0.95f, 1f );
			out.addAll( collector.demonstrate( env, replay, HeroClass.valueOf( replay.heroClass ), FIRST_EPISODE_ID + k ));
		}
		return out;
	}
}
