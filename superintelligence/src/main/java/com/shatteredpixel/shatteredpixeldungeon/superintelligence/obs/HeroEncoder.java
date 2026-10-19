package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Doom;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfForce;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;


public class HeroEncoder {

	public static final int FEATURES = 23;

	public static final int F_HP_FRACTION     = 0;
	public static final int F_HP_ABSOLUTE     = 1;
	public static final int F_MAX_HP_FRACTION = 2;
	public static final int F_DEPTH           = 3;
	public static final int F_DEPTH_BRANCH    = 4;
	public static final int F_LEVEL           = 5;
	public static final int F_EXP_FRACTION    = 6;
	public static final int F_STRENGTH        = 7;
	public static final int F_DEFENSE         = 8;
	public static final int F_GOLD_LOG        = 9;
	public static final int F_FLOOR_EXPLORED  = 10;
	public static final int F_HUNGER          = 11;
	public static final int F_STARVING        = 12;
	public static final int F_BLEEDING        = 13;
	public static final int F_POISON          = 14;
	public static final int F_BURNING         = 15;
	public static final int F_COLD            = 16;
	public static final int F_ROOTED          = 17;
	public static final int F_BLINDED         = 18;
	public static final int F_PARALYSED       = 19;
	public static final int F_DOOM            = 20;
	public static final int F_ARMOR_TIER      = 21;
	public static final int F_WEAPON_TIER     = 22;


	private static final float MAX_LEVEL = 30f;


	private static final float HUNGER_SCALE = 600f;

	private HeroEncoder() {}


	private static int defenseCeiling( Hero hero ){
		int dr = Barkskin.currentLevel( hero );

		Armor armor = hero.belongings.armor();
		if (armor != null){
			dr += armor.levelKnown ? armor.DRMax() : armor.DRMax( 0 );
			int strReq = armor.levelKnown ? armor.STRReq() : armor.STRReq( 0 );
			if (hero.STR() < strReq){
				dr -= 2 * (strReq - hero.STR());
			}
		}

		if (hero.belongings.weapon() instanceof Weapon
				&& !RingOfForce.fightingUnarmed( hero )){
			Weapon weapon = (Weapon) hero.belongings.weapon();
			dr += weapon.defenseFactor( hero );
			int strReq = weapon.levelKnown ? weapon.STRReq() : weapon.STRReq( 0 );
			if (hero.STR() < strReq){
				dr -= 2 * (strReq - hero.STR());
			}
		}

		return Math.max( 0, dr );
	}

	public static void encode( Hero hero, float[] out ){
		for (int i = 0; i < FEATURES; i++) out[ i ] = 0f;

		out[ F_HP_FRACTION ]     = hero.HT > 0 ? (float) hero.HP / hero.HT : 0f;
		out[ F_HP_ABSOLUTE ]     = hero.HT > 0 ? (float) hero.HP / 40f : 0f;
		out[ F_MAX_HP_FRACTION ] = hero.HT > 0 ? (float) hero.HT / 60f : 0f;
		out[ F_DEPTH ]           = (Dungeon.depth - 1) / 25f;
		out[ F_DEPTH_BRANCH ]    = Dungeon.branch;
		out[ F_LEVEL ]           = (hero.lvl - 1) / MAX_LEVEL;
		out[ F_EXP_FRACTION ]    = hero.lvl < MAX_LEVEL
				? (float) hero.exp / Hero.maxExp( hero.lvl ) : 1f;

		out[ F_STRENGTH ] = (hero.STR() - 3) / 40f;
		out[ F_DEFENSE ]  = defenseCeiling( hero ) / 60f;

		out[ F_GOLD_LOG ] = (float) (Math.log( 1 + Dungeon.gold ) / Math.log( 1000 ) );

		if (Dungeon.level != null){
			out[ F_FLOOR_EXPLORED ] = Dungeon.level.levelExplorePercent( Dungeon.depth );
		}

		Hunger hunger = hero.buff( Hunger.class );
		if (hunger != null){
			out[ F_HUNGER ] = Math.min( 1f, hunger.hunger() / HUNGER_SCALE );
			out[ F_STARVING ] = hero.isStarving() ? 1f : 0f;
		}

		out[ F_BLEEDING ]  = buffRemaining( hero, Bleeding.class, 600f );
		out[ F_POISON ]    = buffRemaining( hero, Poison.class, 400f );
		out[ F_BURNING ]   = buffRemaining( hero, Burning.class, 240f );
		out[ F_COLD ]      = buffRemaining( hero, Chill.class, 300f );
		out[ F_ROOTED ]    = hero.rooted ? 1f : 0f;
		out[ F_BLINDED ]   = hero.buff( Blindness.class ) != null ? 1f : 0f;
		out[ F_PARALYSED ] = Math.min( 1f, hero.paralysed / 20f );
		out[ F_DOOM ]      = buffRemaining( hero, Doom.class, 30f );

		out[ F_ARMOR_TIER ] = hero.belongings.armor() != null
				? hero.belongings.armor().tier / 6f : 0f;
		out[ F_WEAPON_TIER ] = hero.belongings.weapon() instanceof MeleeWeapon
				? ((MeleeWeapon) hero.belongings.weapon()).tier / 6f : 0f;
	}


	private static float buffRemaining( Hero hero, Class<? extends Buff> type, float scale ){
		Buff buff = hero.buff( type );
		if (buff == null) return 0f;
		return Math.min( 1f, Math.max( 0f, buff.cooldown() ) / scale );
	}
}