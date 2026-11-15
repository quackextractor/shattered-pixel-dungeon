package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

final class NpcScenarios {

	private NpcScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList(
				quest( "npc.Ghost", Ghost.class, 4, Ghost.Quest::given ),
				quest( "npc.Wandmaker", Wandmaker.class, 9, Wandmaker.Quest::given ),
				quest( "npc.Blacksmith", Blacksmith.class, 14, Blacksmith.Quest::given ),
				quest( "npc.Imp", Imp.class, 19, Imp.Quest::given ));
	}

	private static Scenario quest( String id, Class< ? extends Mob > type, int depth, BooleanSupplier given ){
		return new Scenario( id, "take the " + type.getSimpleName() + "'s quest", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					Mob npc = world.find( type );
					if (npc == null) throw new NoFixture( "floor " + depth + " has no " + type.getSimpleName() );
					world.clearMobs( npc );
					ScrollOfTeleportation.appear( npc, world.adjacentFree() );
				},
				d -> {
					Mob npc = d.world().find( type );
					d.require( npc != null && Dungeon.level.adjacent( npc.pos, Dungeon.hero.pos ), "the NPC is not beside the hero" );
					d.move( npc.pos );
					for (int i = 0; i < 3 && d.env().mode() == EnvMode.MENU; i++) d.menu( 0 );
					d.expect( given.getAsBoolean(), "talking to the " + type.getSimpleName() + " gave no quest" );
					d.expect( d.env().mode() == EnvMode.WORLD, "the quest left the env in " + d.env().mode() );
					d.act( Action.WAIT );
				} ).depth( depth ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}
}
