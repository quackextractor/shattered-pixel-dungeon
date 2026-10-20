package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.RunState;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;


public class RestartCheck {

	private static final String SEED = "RESTART-CHECK";

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "restartcheck" ) );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HeroClass.WARRIOR );

		String first = signature();

		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), SEED.hashCode() );
		int[] slot = new int[ 1 ];
		int played = 0;

		for (int i = 0; i < 400 && env.running(); i++){
			Action action = policy.choose( env, slot );
			if (action == null || env.actionMask()[ action.index ] <= 0.5f ) break;
			env.step( action, slot[ 0 ] );
			played++;
		}

		restart();
		String second = signature();

		if (first.equals( second )){
			System.out.println( "[OK]     restart rebuilt the identical floor 1 after " + played
					+ " turns of play" );
			System.out.println( "         " + first );
			return;
		}

		System.out.println( "[FAIL]   restart did NOT rebuild the same floor 1" );
		System.out.println( "         before: " + first );
		System.out.println( "         after:  " + second );
		System.out.println( "         the replay viewer's R would diverge on its first step" );
		System.exit( 1 );
	}


	private static void restart(){
		RunState.clearRemains();
		Dungeon.hero = null;
		Mob.clearHeldAllies();
		Dungeon.init();
		GameLog.wipe();
		Level level = Dungeon.newLevel();
		Dungeon.switchLevel( level, -1 );
	}


	private static String signature(){
		Level level = Dungeon.level;

		int tiles = 0;
		int[] map = level.map;
		for (int i = 0; i < map.length; i++) tiles = tiles * 31 + map[ i ];

		return "depth=" + Dungeon.depth + " branch=" + Dungeon.branch
				+ " len=" + level.length()
				+ " mapHash=" + tiles
				+ " heaps=" + level.heaps.size
				+ " heroPos=" + (Dungeon.hero == null ? -1 : Dungeon.hero.pos)
				+ " heroHp=" + (Dungeon.hero == null ? -1 : Dungeon.hero.HP)
				+ " items=" + (Dungeon.hero == null ? -1 : Dungeon.hero.belongings.backpack.items.size());
	}
}
