package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.Arrays;
import java.util.List;

final class ConditionScenarios {

	private ConditionScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList( levitation(), invisibility(), slow(), charm(), ankh() );
	}

	private static Scenario levitation(){
		return new Scenario( "buff.Levitation", "levitate over a chasm until it ends", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.terrain( world.adjacentFreeStraight(), Terrain.CHASM );
					kit.buff( Levitation.class, 3f );
				},
				d -> {
					int chasm = d.world().beside( Terrain.CHASM );
					d.move( chasm );
					d.expect( Dungeon.hero.pos == chasm, "a levitating hero was stopped at the chasm" );
					d.waitFor( () -> Dungeon.depth == 2, 6 );
					d.expect( Dungeon.depth == 2, "levitation ran out over the chasm and the hero stayed on depth " + Dungeon.depth );
				} );
	}

	private static Scenario invisibility(){
		return new Scenario( "buff.Invisibility", "an invisible hero is left alone", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new Rat(), world.adjacentFree() );
					kit.buff( Invisibility.class, 6f );
				},
				d -> {
					d.act( Action.WAIT );
					d.act( Action.WAIT );
					d.act( Action.WAIT );
					d.expect( Dungeon.hero.HP == Dungeon.hero.HT, "a rat hit the invisible hero" );
				} );
	}

	private static Scenario slow(){
		return new Scenario( "buff.Slow", "a slowed hero moves at half speed", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.buff( Slow.class, 6f );
				},
				d -> {
					float clock = Actor.now();
					d.move( d.world().adjacentFree() );
					d.expect( Actor.now() - clock >= 2f, "a slowed step took " + (Actor.now() - clock) + " turns" );
				} );
	}

	private static Scenario charm(){
		return new Scenario( "buff.Charm", "a charmed hero cannot strike its charmer", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Rat rat = world.hunting( new Rat(), world.adjacentFree() );
					kit.buff( Charm.class, 4f ).object = rat.id();
				},
				d -> {
					Rat rat = d.world().find( Rat.class );
					int width = Dungeon.level.width();
					int dx = Integer.signum( rat.pos % width - Dungeon.hero.pos % width );
					int dy = Integer.signum( rat.pos / width - Dungeon.hero.pos / width );
					for (Action action : Action.values()){
						if (action.directional() && action.dx == dx && action.dy == dy){
							d.expect( d.env().actionMask()[ action.index ] < 0.5f, "a charmed hero is offered " + action + " into its charmer" );
						}
					}
					d.act( Action.WAIT );
				} );
	}

	private static Scenario ankh(){
		return new Scenario( "ankh", "a blessed ankh brings the hero back", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new Ankh() ).bless();
					kit.health( 1, 20 );
					Rat rat = world.hunting( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 1000;
				},
				d -> {
					d.waitFor( () -> Statistics.ankhsUsed > 0, 20 );
					d.expect( Statistics.ankhsUsed > 0 && Dungeon.hero.isAlive(), "the blessed ankh did not revive the hero" );
					d.expect( d.env().running(), "the revival ended the episode: " + d.env().endReason() );
				} );
	}
}
