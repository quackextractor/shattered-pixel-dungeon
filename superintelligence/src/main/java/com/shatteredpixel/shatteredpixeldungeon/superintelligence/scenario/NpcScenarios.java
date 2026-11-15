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
				quest( "npc.Ghost", Ghost.class, 4, Ghost.Quest::given ).covers( Ghost.class ),
				quest( "npc.Wandmaker", Wandmaker.class, 9, Wandmaker.Quest::given ).covers( Wandmaker.class ),
				quest( "npc.Blacksmith", Blacksmith.class, 14, Blacksmith.Quest::given ).covers( Blacksmith.class ),
				quest( "npc.Imp", Imp.class, 19, Imp.Quest::given ).covers( Imp.class ),
				ghostReward(), subclass().covers( com.shatteredpixel.shatteredpixeldungeon.items.TengusMask.class ));
	}

	private static Scenario ghostReward(){
		return new Scenario( "reward.Ghost", "take the Ghost's reward after a cancelled first pick", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					Mob ghost = world.find( Ghost.class );
					if (ghost == null || Ghost.Quest.weapon == null) throw new NoFixture( "floor 4 has no Ghost" );
					world.clearMobs( ghost );
					ScrollOfTeleportation.appear( ghost, world.adjacentFree() );
					com.watabou.utils.Bundle bundle = new com.watabou.utils.Bundle();
					Ghost.Quest.storeInBundle( bundle );
					com.watabou.utils.Bundle node = bundle.getBundle( "sadGhost" );
					node.put( "given", true );
					node.put( "processed", true );
					node.put( "depth", Dungeon.depth );
					Ghost.Quest.restoreFromBundle( bundle );
				},
				d -> {
					Mob ghost = d.world().find( Ghost.class );
					d.move( ghost.pos );
					d.require( d.env().mode() == EnvMode.MENU, "talking to the Ghost after its quest opened " + d.env().mode() );
					d.require( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.optionCount() == 2,
							"the Ghost's reward offers " + com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.optionCount() + " options, not its weapon and armour" );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.MENU, "picking the weapon opened no confirm: " + d.env().mode() );
					d.menu( 1 );
					d.expect( d.env().mode() == EnvMode.MENU && com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.optionCount() == 2,
							"cancelling the confirm did not return to the reward choice: " + d.env().mode() );
					d.menu( 1 );
					d.menu( 0 );
					d.expect( Ghost.Quest.completed(), "confirming the armour did not complete the quest" );
					d.expect( d.env().mode() == EnvMode.WORLD, "the reward left the env in " + d.env().mode() );
					d.act( Action.WAIT );
				} ).depth( 4 ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}

	private static Scenario subclass(){
		return new Scenario( "reward.subclass", "choose a subclass with Tengu's mask", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new com.shatteredpixel.shatteredpixeldungeon.items.TengusMask() );
				},
				d -> {
					d.use( com.shatteredpixel.shatteredpixeldungeon.items.TengusMask.class );
					d.require( d.env().mode() == EnvMode.MENU, "wearing the mask opened " + d.env().mode() );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.MENU, "picking a subclass opened no confirm: " + d.env().mode() );
					d.menu( 0 );
					d.expect( Dungeon.hero.subClass != com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass.NONE,
							"confirming left the hero without a subclass" );
					d.expect( d.env().mode() == EnvMode.WORLD, "the choice left the env in " + d.env().mode() );
				} );
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
