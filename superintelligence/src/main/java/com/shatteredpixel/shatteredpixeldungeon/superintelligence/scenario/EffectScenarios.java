package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Web;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;

final class EffectScenarios {

	private EffectScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( expires( "buff.Paralysis", Paralysis.class ));
		all.add( expires( "buff.Blindness", Blindness.class ));
		all.add( expires( "buff.Vertigo", Vertigo.class ));
		all.add( rooted() );
		all.add( hurts( "buff.Burning", ( world, kit ) -> kit.buff( Burning.class ).reignite( kit.hero() )));
		all.add( hurts( "buff.Poison", ( world, kit ) -> kit.buff( Poison.class ).set( 6 )));
		all.add( web() );
		for (Class< ? extends Blob > blob : List.of( Fire.class, ToxicGas.class, ParalyticGas.class, ConfusionGas.class,
				Electricity.class, Freezing.class )){
			all.add( hurts( "blob." + blob.getSimpleName(), ( world, kit ) -> world.blob( blob, world.hero(), 100 )));
		}
		return all;
	}

	private static Scenario expires( String id, Class< ? extends FlavourBuff > type ){
		return new Scenario( id, type.getSimpleName() + " runs out", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.buff( type, 3f );
				},
				d -> {
					float clock = Actor.now();
					d.waitFor( () -> Dungeon.hero.buff( type ) == null, 6 );
					d.expect( Dungeon.hero.buff( type ) == null, type.getSimpleName() + " outlasted 6 waits" );
					d.expect( Actor.now() > clock, "waiting under " + type.getSimpleName() + " spent no time" );
				} );
	}

	private static Scenario rooted(){
		return new Scenario( "buff.Roots", "roots hold the hero in place", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.buff( Roots.class, 3f );
				},
				d -> {
					boolean anyMove = false;
					for (Action action : Action.values()){
						if (action.name().startsWith( "MOVE_" )) anyMove |= d.env().actionMask()[ action.index ] > 0.5f;
					}
					d.expect( !anyMove, "a rooted hero is offered a move" );
					d.waitFor( () -> Dungeon.hero.buff( Roots.class ) == null, 6 );
					d.expect( Dungeon.hero.buff( Roots.class ) == null, "Roots outlasted 6 waits" );
				} );
	}

	private static Scenario hurts( String id, Scenario.Setup affect ){
		return new Scenario( id, id + " acts on the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					affect.build( world, kit );
				},
				d -> {
					float clock = Actor.now();
					d.waitFor( () -> touched() || Actor.now() > clock + 6, 5 );
					d.expect( touched() || Actor.now() > clock + 6, id + " neither hurt nor hindered the hero in 5 turns" );
				} );
	}

	private static Scenario web(){
		return new Scenario( "blob.Web", "a web roots the hero who walks into it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.blob( Web.class, world.adjacentFreeStraight(), 100 );
				},
				d -> {
					int cell = -1;
					Blob web = Dungeon.level.blobs.get( Web.class );
					for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
						if (web != null && web.cur[ Dungeon.hero.pos + offset ] > 0) cell = Dungeon.hero.pos + offset;
					}
					d.require( cell >= 0, "no web beside the hero" );
					d.move( cell );
					d.expect( Dungeon.hero.buff( Roots.class ) != null, "walking into a web did not root the hero" );
				} );
	}

	private static boolean touched(){
		if (Dungeon.hero.HP < Dungeon.hero.HT) return true;
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE) return true;
		return false;
	}
}
