package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.File;
import java.io.IOException;


public final class PressFixture {

	private PressFixture() {}


	private static final int STEPS = 60;

	private static final String[] SEEDS = {
			"PRESS-THROW-A", "PRESS-THROW-B", "PRESS-THROW-C", "PRESS-THROW-D", "PRESS-THROW-E",
			"PRESS-THROW-F", "PRESS-THROW-G", "PRESS-THROW-H"
	};

	private static final HeroClass HERO = HeroClass.DUELIST;


	public static Replay recordAndWrite( EnvConfig config, File file ) throws IOException {
		Replay replay = record( config );
		ReplayIO.write( replay, file );
		return replay;
	}


	public static Replay record( EnvConfig config ){
		for (String seed : SEEDS){
			Replay recorded = attempt( config, seed );
			if (recorded != null) return recorded;
		}
		throw new IllegalStateException( "no seed gave the hero a throwable beside high grass on floor 1,"
				+ " so the recording would contain no throw that presses anything." );
	}


	private static Replay attempt( EnvConfig config, String seed ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HERO );

		int slot = throwableSlot( env );
		int target = grassTarget( env );
		if (slot < 0 || target < 0) return null;

		int grass = env.mapper().targetCell( target );
		int before = Dungeon.level.map[grass];

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HERO.name(), 0, config.turnLimitPerFloor, config );
		Replay replay = recorder.replay();

		step( env, recorder, Action.USE, 0, EnvMode.WORLD );
		step( env, recorder, Action.USE, slot, EnvMode.SLOT );

		if (env.mode() != EnvMode.TARGETING) return null;

		step( env, recorder, Action.USE, target, EnvMode.TARGETING );

		if (Dungeon.level.map[grass] == Terrain.HIGH_GRASS) return null;

		System.out.println( "  the throw pressed its landing cell: " + seed + ", "
				+ env.mapper().slot( slot ).getClass().getSimpleName() + " thrown at cell " + grass
				+ ", terrain " + before + " -> " + Dungeon.level.map[grass] );

		int[] secondary = new int[ 1 ];
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 3 );
		while (env.running() && replay.length() < STEPS){
			Action action = policy.choose( env, secondary );
			step( env, recorder, action, secondary[ 0 ], env.mode() );
		}

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );
		return replay;
	}


	private static int throwableSlot( SPDEnv env ){
		env.mapper().refreshSlots();
		for (int i = 0; i < env.mapper().slots().size(); i++){
			Item item = env.mapper().slot( i );
			if (item == null || !item.usesTargeting) continue;
			if (Dungeon.quickslot.getSlot( item ) >= 0 && !item.isEquipped( Dungeon.hero )) return i;
		}
		return -1;
	}


	private static int grassTarget( SPDEnv env ){
		for (int o = 1; o < ActionMapper.TARGET_COUNT; o++){
			int cell = env.mapper().targetCell( o );
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (Dungeon.level.map[cell] != Terrain.HIGH_GRASS) continue;
			if (Dungeon.level.heaps.containsKey(cell) || Dungeon.level.plants.containsKey(cell)) continue;
			if (Actor.findChar( cell ) != null) continue;
			return o;
		}
		return -1;
	}


	private static void step( SPDEnv env, ReplayRecorder recorder, Action action, int secondary,
			EnvMode mode ){
		recorder.record( action, secondary, mode );
		float reward = (float) env.step( action, secondary );
		recorder.afterStep( env.heroPosition(), reward );
	}
}
