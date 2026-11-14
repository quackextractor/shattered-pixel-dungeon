package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.Arrays;
import java.util.List;

final class LeakScenarios {

	private LeakScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList(
				leak( "leak.aim", EnvMode.TARGETING, ( world, kit ) -> {
					kit.give( new WandOfMagicMissile() );
					world.mob( new Rat(), world.cellAtRange( 3, true ));
				}, d -> d.use( WandOfMagicMissile.class )),
				leak( "leak.picker", EnvMode.INVENTORY, ( world, kit ) -> kit.give( new ScrollOfUpgrade() ),
						d -> d.use( ScrollOfUpgrade.class )),
				leak( "leak.menu", EnvMode.MENU, ( world, kit ) -> kit.give( new LeatherArmor() ),
						d -> d.use( LeatherArmor.class )),
				leak( "leak.sell", EnvMode.INVENTORY, ( world, kit ) -> world.mob( new Shopkeeper(), world.adjacentFree() ),
						d -> {
							d.step( Action.INTERACT, 0 );
							d.require( d.env().mode() == EnvMode.MENU, "the Shopkeeper asked nothing: " + d.env().mode() );
							d.menu( 0 );
						} ),
				leak( "leak.death", null, ( world, kit ) -> {
					kit.health( 1, 20 );
					world.hunting( new Rat(), world.adjacentFree() );
				}, d -> d.waitFor( () -> !Dungeon.hero.isAlive(), 30 ))
		);
	}

	private static Scenario leak( String id, EnvMode dirty, Scenario.Setup setup, Scenario.Script enter ){
		return new Scenario( id, "end an episode in " + (dirty == null ? "death" : dirty.name()), HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					setup.build( world, kit );
				},
				d -> {
					enter.run( d );
					if (dirty == null) d.expect( !d.env().running(), "the hero did not die" );
					else d.expect( d.env().mode() == dirty, "the episode ends in " + d.env().mode() + ", not " + dirty );
				} ).dirty();
	}
}
