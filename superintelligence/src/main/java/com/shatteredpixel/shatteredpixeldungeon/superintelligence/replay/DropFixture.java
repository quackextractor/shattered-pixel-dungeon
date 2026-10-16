package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.File;
import java.io.IOException;

public final class DropFixture {

	private DropFixture() {}

	private static final int STEPS = 60;

	private static final String SEED = "DROP-IN-SLOT";

	private static final HeroClass HERO = HeroClass.DUELIST;

	public static Replay recordAndWrite( EnvConfig config, File file ) throws IOException {
		Replay replay = record( config );
		ReplayIO.write( replay, file );
		return replay;
	}

	public static Replay record( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HERO );

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HERO.name(), 0, config.turnLimitPerFloor, config );
		Replay replay = recorder.replay();

		env.mapper().refreshSlots();
		int slot = -1;
		for (int i = 0; i < env.mapper().slots().size(); i++){
			Item item = env.mapper().slot( i );
			if (item == null) continue;
			if (Dungeon.quickslot.getSlot( item ) >= 0 && !item.isEquipped( Dungeon.hero )){
				slot = i;
				break;
			}
		}
		if (slot < 0){
			throw new IllegalStateException( SEED + " carries nothing bound and unequipped, so the"
					+ " fixture cannot drop an item whose slot resolution the bindings decide." );
		}

		step( env, recorder, Action.USE, 0, EnvMode.WORLD );
		step( env, recorder, Action.DROP, slot, EnvMode.SLOT );

		int[] secondary = new int[ 1 ];
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 3 );
		while (env.running() && recorder.replay().length() < STEPS){
			Action action = policy.choose( env, secondary );
			step( env, recorder, action, secondary[ 0 ], env.mode() );
		}

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );
		return replay;
	}

	private static void step( SPDEnv env, ReplayRecorder recorder, Action action, int secondary,
			EnvMode mode ){
		recorder.record( action, secondary, mode );
		float reward = (float) env.step( action, secondary );
		recorder.afterStep( env.heroPosition(), reward );
	}
}
