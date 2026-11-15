package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Annoying;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Dazzling;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Displacing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Explosive;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Friendly;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Polarized;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Pressurized;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Sacrificial;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Wayward;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.curses.Wondrous;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.EnchantScenarios.TARGET;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.EnchantScenarios.proc;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.EnchantScenarios.ratGains;

final class WeaponCurseScenarios {

	private WeaponCurseScenarios(){}

	private static final int EXPLOSIVE_DURABILITY = 30;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( proc( "wcurse.Annoying", Annoying.class, TARGET, () -> {
			Mob rat = PotionScenarios.rat();
			return () -> rat.state != rat.PASSIVE;
		}, "the annoying weapon never alerted the passive rat" ).seeds( "SCN-0" ));
		all.add( proc( "wcurse.Dazzling", Dazzling.class, TARGET, () -> () -> Dungeon.hero.buff( Blindness.class ) != null,
				"the dazzling weapon never blinded the hero" ));
		all.add( proc( "wcurse.Displacing", Displacing.class, TARGET, () -> {
			Mob rat = PotionScenarios.rat();
			int cell = rat.pos;
			return () -> rat.pos != cell;
		}, "the displacing weapon never teleported the rat" ));
		all.add( proc( "wcurse.Explosive", Explosive.class, ( world, kit ) -> {
			Bundle bundle = new Bundle();
			bundle.put( "durability", EXPLOSIVE_DURABILITY );
			EnchantScenarios.weapon().enchantment.restoreFromBundle( bundle );
			TARGET.build( world, kit );
		}, () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.HP < hp && EnchantScenarios.durability() > EXPLOSIVE_DURABILITY;
		}, "the explosive weapon never blew up beside the hero" ));
		all.add( proc( "wcurse.Friendly", Friendly.class, TARGET, ratGains( Charm.class ), "the friendly weapon never charmed the rat" ));
		all.add( proc( "wcurse.Polarized", Polarized.class, TARGET, WeaponCurseScenarios::polarized,
				"the polarized weapon never dealt more than an unpolarized hit can" ).seeds( "SCN-1" ));
		all.add( proc( "wcurse.Pressurized", Pressurized.class, EnchantScenarios.DRY, () -> {
			int water = EnchantScenarios.cells( Terrain.WATER );
			return () -> EnchantScenarios.cells( Terrain.WATER ) > water;
		}, "the pressurized weapon never burst a geyser" ));
		all.add( proc( "wcurse.Sacrificial", Sacrificial.class, TARGET, () -> () -> Dungeon.hero.buff( Bleeding.class ) != null,
				"the sacrificial weapon never made the hero bleed" ));
		all.add( proc( "wcurse.Wayward", Wayward.class, TARGET, () -> {
			Mob rat = PotionScenarios.rat();
			int accuracy = Dungeon.hero.attackSkill( rat );
			return () -> Dungeon.hero.attackSkill( rat ) < accuracy;
		}, "the wayward weapon never lowered the hero's accuracy" ));
		all.add( proc( "wcurse.Wondrous", Wondrous.class, TARGET, () -> {
			String before = sideEffects();
			return () -> !sideEffects().equals( before );
		}, "the wondrous weapon never produced a cursed wand effect" ));
		return all;
	}

	private static BooleanSupplier polarized(){
		Mob rat = PotionScenarios.rat();
		int plainMax = EnchantScenarios.plainMax();
		int[] last = { rat.HP };
		boolean[] amplified = { false };
		return () -> {
			amplified[ 0 ] |= last[ 0 ] - rat.HP > plainMax;
			last[ 0 ] = rat.HP;
			return amplified[ 0 ];
		};
	}

	private static String sideEffects(){
		List< String > rows = new ArrayList<>();
		rows.add( "hero " + Dungeon.depth + " " + Dungeon.hero.pos + " " + buffs( Dungeon.hero ));
		for (Mob mob : Dungeon.level.mobs){
			rows.add( "mob " + mob.getClass().getSimpleName() + " " + mob.pos + " " + mob.alignment + " " + buffs( mob ));
		}
		for (Heap heap : Dungeon.level.heaps.valueList()){
			StringBuilder row = new StringBuilder( "heap " ).append( heap.pos );
			for (Item item : heap.items) row.append( ' ' ).append( item.getClass().getSimpleName() );
			rows.add( row.toString() );
		}
		for (Blob blob : Dungeon.level.blobs.values()) if (blob.volume > 0) rows.add( "blob " + blob.getClass().getSimpleName() );
		rows.add( "terrain " + Arrays.hashCode( Dungeon.level.map ));
		Collections.sort( rows );
		return String.join( "\n", rows );
	}

	private static String buffs( Char ch ){
		List< String > names = new ArrayList<>();
		for (Buff buff : ch.buffs()) names.add( buff.getClass().getSimpleName() );
		Collections.sort( names );
		return String.join( "+", names );
	}
}
