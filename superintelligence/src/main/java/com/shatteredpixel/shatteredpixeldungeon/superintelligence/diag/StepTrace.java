package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;

/** TEMP: trainer-side per-step trace of a recording, to diff against the viewer trace. */
public class StepTrace {
	public static void main( String[] a ) throws Exception {
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-steptrace" ));
		HeadlessServices.disableSaving( true );

		Replay r = ReplayIO.read( new File( a[ 0 ] ));
		int n = a.length > 1 ? Integer.parseInt( a[ 1 ] ) : 10;

		EnvConfig c = new EnvConfig();
		c.turnLimitPerFloor = r.turnLimitPerFloor;
		SPDEnv env = new SPDEnv( c, HeadlessGame.install() );
		env.reset( r.seedText, HeroClass.valueOf( r.heroClass ) );

		System.out.println( "width=" + Dungeon.level.width() + " start=" + env.heroPosition()
				+ " pouch=" + hasPouch( Dungeon.hero )
				+ " bagCount=" + Dungeon.hero.belongings.getBags().size() );

		for (int i = 0; i < n && env.running(); i++ ){
			Replay.Step s = r.steps.get( i );
			Action act = Action.valueOf( s.action );
			int before = env.heroPosition();
			env.step( act, s.slot );
			int after = env.heroPosition();

		boolean bad = after != s.heroPos;
			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero h = Dungeon.hero;
			int used = 0;
			for (com.shatteredpixel.shatteredpixeldungeon.items.Item it : h.belongings.backpack) used++;
			StringBuilder bagNames = new StringBuilder();
			for (com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag b : h.belongings.getBags()){
				bagNames.append( b.getClass().getSimpleName() ).append( " " );
			}
			System.out.println( String.format( "  [%d] %-16s before=%d after=%d recorded=%d turn=%s resting=%s backpack=%d/%d bags=%s%s",
					i, act + "/" + s.slot, before, after, s.heroPos,
					String.valueOf( Actor.now() ), String.valueOf( Dungeon.hero.resting ),
					used, h.belongings.backpack.capacity(), bagNames.toString().trim(),
					bad ? "   <<<" : "" ) );
		}
	}

	private static String hasPouch( com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero ){
		for (com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag b : hero.belongings.getBags()){
			if (b.getClass().getSimpleName().equals( "VelvetPouch" )) return "yes";
		}
		return "no";
	}
}
