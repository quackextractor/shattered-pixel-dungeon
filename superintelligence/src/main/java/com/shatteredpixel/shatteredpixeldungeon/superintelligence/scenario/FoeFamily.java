package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.Reflection;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class FoeFamily {

	private FoeFamily(){}

	static final int MIN_HP = 40;

	static Scenario fight( String id, Class< ? extends Mob > type, int range, int steps, BooleanSupplier signature, String failure ){
		return fight( id, type, range, steps, signature, failure, ( world, kit ) -> {} );
	}

	static Scenario fight( String id, Class< ? extends Mob > type, int range, int steps, BooleanSupplier signature, String failure,
			Scenario.Setup arena ){
		return new Scenario( id, type.getSimpleName() + " fights the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					arena.build( world, kit );
					int cell = range == 1 ? world.adjacentFree() : world.cellAtRange( range, true );
					Mob mob = world.hunting( spawn( type, cell ), cell );
					mob.HP = mob.HT = Math.max( mob.HT, MIN_HP );
				},
				d -> {
					Mob mob = d.world().find( type );
					Hero hero = Dungeon.hero;
					int heroHp = hero.HP;
					int mobHp = mob.HP;
					Set< Buff > buffs = new HashSet<>( hero.buffs() );
					boolean hurt = false;
					boolean effect = false;
					for (int i = 0; i < steps && d.env().running() && !(hurt && effect); i++){
						if (mob.isAlive() && Dungeon.level.adjacent( hero.pos, mob.pos )) d.move( mob.pos );
						else d.act( Action.WAIT );
						hurt |= !mob.isAlive() || mob.HP < mobHp;
						effect |= signature != null ? signature.getAsBoolean() : hero.HP < heroHp || newDebuff( hero, buffs );
					}
					d.expect( hurt, "the " + type.getSimpleName() + " took no damage in " + steps + " steps" );
					d.expect( effect, failure );
				} ).covers( type );
	}

	static Scenario passive( String id, Class< ? extends Mob > type, Scenario.Script interact, BooleanSupplier changed, String failure ){
		return new Scenario( id, "interacting with a " + type.getSimpleName() + " changes it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int cell = world.adjacentFree();
					world.mob( spawn( type, cell ), cell );
				},
				d -> {
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
