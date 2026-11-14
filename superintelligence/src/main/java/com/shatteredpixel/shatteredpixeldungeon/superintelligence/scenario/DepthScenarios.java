package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;

import java.util.Arrays;
import java.util.List;

final class DepthScenarios {

	private DepthScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList( depthJump(), stairs(), gooFight() );
	}

	private static Scenario depthJump(){
		return new Scenario( "depth-jump", "start a scenario on floor 3", HeroClass.WARRIOR,
				( world, kit ) -> world.clearMobs(),
				d -> {
					d.expect( Dungeon.depth == 3, "the jump landed on depth " + Dungeon.depth );
					d.act( Action.WAIT );
				} ).depth( 3 );
	}

	private static Scenario stairs(){
		return new Scenario( "stairs", "go down the stairs and back up", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					java.util.Arrays.fill( world.level().mapped, true );
				},
				d -> {
					String[] floor = { "" };
					d.until( () -> Dungeon.depth == 2, 100, () -> {
						floor[ 0 ] = java.util.Arrays.toString( Dungeon.level.map );
						d.act( Action.TRAVEL_STAIRS );
					});
					d.require( Dungeon.depth == 2, "100 TRAVEL_STAIRS steps did not descend" );
					LevelTransition entrance = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_ENTRANCE );
					d.require( entrance != null && entrance.cell() == Dungeon.hero.pos, "the hero did not land on the entrance" );
					int stairs = Dungeon.hero.pos;
					d.move( d.world().adjacentFree() );
					d.move( stairs );
					d.expect( Dungeon.depth == 1, "stepping back onto the entrance left the hero on depth " + Dungeon.depth );
					d.expect( floor[ 0 ].equals( java.util.Arrays.toString( Dungeon.level.map )), "floor 1 came back different" );
				} );
	}

	private static Scenario gooFight(){
		return new Scenario( "boss.goo5", "fight Goo on floor 5", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					Goo goo = null;
					for (Mob mob : world.level().mobs) if (mob instanceof Goo) goo = (Goo) mob;
					if (goo == null) throw new NoFixture( "floor 5 has no Goo" );
					ScrollOfTeleportation.appear( goo, world.cellAtRange( 2, true ));
					goo.aggro( Dungeon.hero );
					goo.state = goo.HUNTING;
				},
				d -> {
					Goo goo = d.world().find( Goo.class );
					DescentBot bot = new DescentBot();
					int[] secondary = new int[ 1 ];
					d.until( () -> goo.HP < goo.HT, 80, () -> {
						Action action = bot.act( d.env(), secondary );
						d.step( action.kind == Action.Kind.MACRO ? bot.primitive( action ) : action, secondary[ 0 ] );
					});
					d.expect( goo.HP < goo.HT || !goo.isAlive(), "80 teacher steps beside Goo left it untouched" );
					d.expect( d.env().running(), "the fight ended the episode: " + d.env().endReason() );
				} ).depth( 5 ).botDriven( 80 );
	}
}
