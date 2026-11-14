package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.AlarmTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DisintegrationTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GrippingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.PitfallTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.PoisonDartTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.RockfallTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.SummoningTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.TeleportationTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ToxicTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.WornDartTrap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class TrapScenarios {

	private TrapScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		List< Supplier< Trap > > traps = List.of( WornDartTrap::new, PoisonDartTrap::new, BurningTrap::new, ToxicTrap::new,
				TeleportationTrap::new, SummoningTrap::new, AlarmTrap::new, GrippingTrap::new, DisintegrationTrap::new,
				RockfallTrap::new, PitfallTrap::new );
		for (Supplier< Trap > trap : traps) all.add( stepOn( trap ));
		all.add( search() );
		return all;
	}

	private static Scenario stepOn( Supplier< Trap > make ){
		String name = make.get().getClass().getSimpleName().replace( "Trap", "" );
		return new Scenario( "trap." + name, "step on a " + name + " trap", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.trap( make.get(), world.adjacentFreeStraight(), Terrain.TRAP );
				},
				d -> {
					Trap trap = adjacentTrap();
					int cell = trap.pos, depth = Dungeon.depth;
					d.move( cell );
					if (trap instanceof PitfallTrap) d.waitFor( () -> Dungeon.depth != depth, 3 );
					boolean fired = !trap.active || Dungeon.depth != depth
							|| (trap instanceof GrippingTrap && Dungeon.hero.buff( Cripple.class ) != null);
					d.expect( fired, "the " + name + " trap at " + cell + " did not fire, hero at " + Dungeon.hero.pos );
					if (trap instanceof PitfallTrap) d.expect( Dungeon.depth == depth + 1, "the pitfall left the hero on depth " + Dungeon.depth );
				} );
	}

	private static Trap adjacentTrap(){
		for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
			Trap trap = Dungeon.level.traps.get( Dungeon.hero.pos + offset );
			if (trap != null) return trap;
		}
		throw new Driver.Stop( "no trap beside the hero" );
	}

	private static Scenario search(){
		return new Scenario( "trap-search", "search out a hidden trap", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.trap( new WornDartTrap(), world.adjacentFreeStraight(), Terrain.SECRET_TRAP );
				},
				d -> {
					Trap trap = adjacentTrap();
					d.require( !trap.visible, "the trap was visible before the search" );
					d.until( () -> trap.visible, 5, () -> d.act( Action.SEARCH ));
					d.expect( trap.visible, "five searches left the trap at " + trap.pos + " hidden" );
				} );
	}
}
