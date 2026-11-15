package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.Reflection;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class FoeFamily {

	private FoeFamily(){}

	static final int MIN_HP = 40;

	static Scenario fight( String id, Class< ? extends Mob > type, int range, int steps, BooleanSupplier signature, String failure ){
		return fight( id, type, range, steps, signature, failure, ( world, kit ) -> {} );
	}

	static Scenario fight( String id, Class< ? extends Mob > type, int range, int steps, BooleanSupplier signature, String failure,
			Scenario.Setup arena ){
		return armedFight( id, type, range, steps, signature == null ? null : () -> signature, failure, arena );
	}

	static Scenario armedFight( String id, Class< ? extends Mob > type, int range, int steps, Supplier< BooleanSupplier > arm,
			String failure, Scenario.Setup arena ){
		return new Scenario( id, type.getSimpleName() + " fights the hero", HeroClass.WARRIOR, setup( type, range, arena ),
				d -> brawl( d, type, steps, arm, failure ) ).covers( type );
	}

	static Scenario dot( String id, Class< ? extends Mob > type, Class< ? extends Buff > dot, String applied, String hurts ){
		return new Scenario( id, type.getSimpleName() + " leaves a " + dot.getSimpleName() + " that hurts on its own", HeroClass.WARRIOR,
				setup( type, 1, ( world, kit ) -> kit.give( new ScrollOfTeleportation() )),
				d -> outlast( d, brawl( d, type, 12, () -> () -> Dungeon.hero.buff( dot ) != null, applied ), dot, hurts ) )
				.covers( type ).covers( dot );
	}

	private static Scenario.Setup setup( Class< ? extends Mob > type, int range, Scenario.Setup arena ){
		return ( world, kit ) -> {
			world.clearMobs();
			kit.sturdy();
			arena.build( world, kit );
			int cell = range == 1 ? world.adjacentFree() : world.cellAtRange( range, true );
			Mob mob = world.hunting( spawn( type, cell ), cell );
			mob.HP = mob.HT = Math.max( mob.HT, MIN_HP );
		};
	}

	private static Mob brawl( Driver d, Class< ? extends Mob > type, int steps, Supplier< BooleanSupplier > arm, String failure ){
		Mob mob = d.world().find( type );
		Hero hero = Dungeon.hero;
		int heroHp = hero.HP;
		int mobHp = mob.HP;
		Set< Buff > buffs = new HashSet<>( hero.buffs() );
		BooleanSupplier signature = arm == null ? null : arm.get();
		d.require( signature == null || !signature.getAsBoolean(), "the " + type.getSimpleName() + "'s signature held before the fight" );
		boolean hurt = false;
		boolean effect = false;
		for (int i = 0; i < steps && d.env().running() && !(hurt && effect); i++){
			if (reaches( mob )) d.move( mob.pos );
			else d.act( Action.WAIT );
			hurt |= !mob.isAlive() || mob.HP < mobHp;
			effect |= signature != null ? signature.getAsBoolean() : hero.HP < heroHp || newDebuff( hero, buffs );
		}
		d.expect( hurt, "the " + type.getSimpleName() + " took no damage in " + steps + " steps" );
		d.expect( effect, failure );
		return mob;
	}

	private static void outlast( Driver d, Mob mob, Class< ? extends Buff > dot, String failure ){
		Hero hero = Dungeon.hero;
		d.require( hero.buff( dot ) != null, "the " + dot.getSimpleName() + " wore off before the hero could get away" );
		int hp = hero.HP;
		d.use( ScrollOfTeleportation.class );
		boolean fell = !reaches( mob ) && hero.HP < hp;
		for (int i = 0; i < 6 && !fell && hero.buff( dot ) != null && d.env().running(); i++){
			boolean near = reaches( mob );
			hp = hero.HP;
			d.act( Action.WAIT );
			fell = !near && !reaches( mob ) && hero.HP < hp;
		}
		d.expect( fell, failure );
	}

	private static boolean reaches( Mob mob ){
		return mob.isAlive() && Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos );
	}

	static Scenario passive( String id, Class< ? extends Mob > type, Scenario.Script interact, BooleanSupplier changed, String failure ){
		return armedPassive( id, type, interact, () -> changed, failure );
	}

	static Scenario armedPassive( String id, Class< ? extends Mob > type, Scenario.Script interact, Supplier< BooleanSupplier > arm,
			String failure ){
		return new Scenario( id, "interacting with a " + type.getSimpleName() + " changes it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int cell = world.adjacentFree();
					world.mob( spawn( type, cell ), cell );
				},
				d -> {
					BooleanSupplier changed = arm.get();
					d.require( !changed.getAsBoolean(), "the " + type.getSimpleName() + " had changed before the interaction" );
					interact.run( d );
					d.expect( changed.getAsBoolean(), failure );
				} ).covers( type );
	}

	private static Mob spawn( Class< ? extends Mob > type, int cell ){
		return Mimic.class.isAssignableFrom( type ) ? Mimic.spawnAt( cell, type ) : Reflection.newInstance( type );
	}

	private static boolean newDebuff( Hero hero, Set< Buff > before ){
		for (Buff buff : hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE && !before.contains( buff )) return true;
		return false;
	}
}
