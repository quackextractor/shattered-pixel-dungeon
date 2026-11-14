package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Guard;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;

import java.util.Arrays;
import java.util.List;

final class CombatScenarios {

	private CombatScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList( melee(), guardChain() );
	}

	private static Scenario melee(){
		return new Scenario( "melee", "melee attack", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new Rat(), world.adjacentFree() );
				},
				d -> {
					Rat rat = d.world().find( Rat.class );
					int slain = Statistics.enemiesSlain;
					d.until( () -> !rat.isAlive(), 20, () -> d.move( rat.pos ));
					d.expect( !rat.isAlive(), "the rat survived 20 attacks" );
					d.expect( Statistics.enemiesSlain == slain + 1, "enemiesSlain moved by "
							+ (Statistics.enemiesSlain - slain) + ", not 1" );
				} );
	}

	private static Scenario guardChain(){
		return new Scenario( "guard-chain", "mob pulls the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new Guard(), world.cellAtRange( 3, true ));
				},
				d -> {
					Guard guard = d.world().find( Guard.class );
					d.waitFor( guard::chainsUsed, 3 );
					d.fixture( guard.chainsUsed(), "the guard walked in without throwing its chain" );
					d.expect( Dungeon.level.adjacent( Dungeon.hero.pos, guard.pos ), "the guard threw its chain"
							+ " and the hero stayed at " + Dungeon.hero.pos + ", guard at " + guard.pos );
				} );
	}
}
