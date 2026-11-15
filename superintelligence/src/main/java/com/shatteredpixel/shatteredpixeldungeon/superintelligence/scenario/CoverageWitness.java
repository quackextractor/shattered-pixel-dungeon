package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CoverageWitness {

	private final Set< String > seen = new HashSet<>();

	void sample(){
		Level level = Dungeon.level;
		Hero hero = Dungeon.hero;
		if (level == null || hero == null) return;
		seen.add( Coverage.key( level.getClass() ));
		seen.add( Coverage.heroClass( hero.heroClass ));
		if (hero.armorAbility != null) seen.add( Coverage.key( hero.armorAbility.getClass() ));
		buffs( hero.buffs() );
		for (Mob mob : level.mobs){
			seen.add( Coverage.key( mob.getClass() ));
			buffs( mob.buffs() );
		}
		for (Blob blob : level.blobs.values()) if (blob.volume > 0) seen.add( Coverage.key( blob.getClass() ));
		for (Item item : hero.belongings) seen.add( Coverage.key( item.getClass() ));
		for (Heap heap : level.heaps.valueList()) for (Item item : heap.items) seen.add( Coverage.key( item.getClass() ));
		if (hero.belongings.weapon instanceof Weapon && ((Weapon) hero.belongings.weapon).enchantment != null){
			seen.add( Coverage.key( ((Weapon) hero.belongings.weapon).enchantment.getClass() ));
		}
		if (hero.belongings.armor != null && hero.belongings.armor.glyph != null){
			seen.add( Coverage.key( hero.belongings.armor.glyph.getClass() ));
		}
		for (Trap trap : level.traps.valueList()) seen.add( Coverage.key( trap.getClass() ));
		for (Plant plant : level.plants.valueList()) seen.add( Coverage.key( plant.getClass() ));
		if (level instanceof RegularLevel){
			for (Room room : ((RegularLevel) level).rooms()) seen.add( Coverage.key( room.getClass() ));
		}
	}

	private void buffs( Set< Buff > buffs ){
		for (Buff buff : buffs) seen.add( Coverage.key( buff.getClass() ));
	}

	void chosen( String key ){
		seen.add( key );
	}

	void witness( String key ){
		if (!Coverage.extra().contains( key ) && !clericSpell( key )){
			throw new Driver.Stop( "witness() is only for keys with no observable instance: " + key );
		}
		seen.add( key );
	}

	private static boolean clericSpell( String key ){
		try {
			return ClericSpell.class.isAssignableFrom( Class.forName( Coverage.PREFIX + key, false,
					CoverageWitness.class.getClassLoader() ));
		} catch (ClassNotFoundException e){
			return false;
		}
	}

	List< String > unwitnessed( Set< String > declared ){
		List< String > out = new ArrayList<>();
		for (String key : declared) if (!seen.contains( key )) out.add( key );
		return out;
	}
}
