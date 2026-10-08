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

/**
 * Encodes the hero's scalars: vitals, progression, and the status effects that gate which actions
 * are worth taking.
 *
 * Every value is normalised into roughly [-1, 1]. These feed the LSTM concatenated with the
 * spatial features, so an unbounded quantity like gold would otherwise dominate the activations
 * and wash out the spatial signal.
 *
 * Buff strength is read through {@code Buff.cooldown()}, which is time remaining. Duration buffs
 * are normalised by a typical maximum rather than their own total, so that a Poison at 200 turns
 * reads consistently the same way whether it was applied as Poison or a resist-adjusted variant.
 */
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

	/** Hero.MAX_LEVEL, used to normalise experience progress. */
	private static final float MAX_LEVEL = 30f;

	/** Hunger.HUNGRY is the point at which the hero becomes hungry; the full bar runs further. */
	private static final float HUNGER_SCALE = 600f;

	private HeroEncoder() {}

	/**
	 * The largest damage reduction this hero could roll, which is what {@code Hero.drRoll} is a draw
	 * from.
	 *
	 * <p>{@code drRoll} is not a property of the hero - it is a fresh {@code Random.NormalIntRange} on
	 * every call, and {@code Hero.drRoll} draws once for Barkskin, once for armour and once for the
	 * weapon. Calling it to build an observation was therefore wrong twice over. It perturbed the
	 * game's own randomness on every encode - twice per agent step, once from {@code reset} and once
	 * from {@code settle} - which is what offset the trainer's RNG stream from the rendered game's
	 * from the very first floor, and no recording made since could ever replay. And the feature itself
	 * was noise: two identical worlds encoded to different vectors, so the agent was shown a number
	 * that carried no information about the state it had to act on.
	 *
	 * <p>Taking each component's maximum instead yields the roll's ceiling, which is deterministic,
	 * is a real property of the loadout, and leaves the game's randomness alone.
	 */
	private static int defenseCeiling( Hero hero ){
		int dr = Barkskin.currentLevel( hero );

		Armor armor = hero.belongings.armor();
		if (armor != null){
			dr += armor.DRMax();
			if (hero.STR() < armor.STRReq()){
				dr -= 2 * (armor.STRReq() - hero.STR());
			}
		}

		if (hero.belongings.weapon() instanceof Weapon
				&& !RingOfForce.fightingUnarmed( hero )){
			Weapon weapon = (Weapon) hero.belongings.weapon();
			dr += weapon.defenseFactor( hero );
			if (hero.STR() < weapon.STRReq()){
				dr -= 2 * (weapon.STRReq() - hero.STR());
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

		//gold spans three orders of magnitude across a run, so it is logged
		out[ F_GOLD_LOG ] = (float) (Math.log( 1 + Dungeon.gold ) / Math.log( 1000 ) );

		if (Dungeon.level != null){
			out[ F_FLOOR_EXPLORED ] = Dungeon.level.levelExplorePercent( Dungeon.depth );
		}

		//hunger drives the docs.md "punish eating early" term, so the agent must be able to see it
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
		out[ F_WEAPON_TIER ] = hero.belongings.weapon() != null
				? weaponTier( hero ) / 6f : 0f;
	}

	/** Weapon tier, normalised. KindOfWeapon has no tier field of its own, so it is read off the item level. */
	private static float weaponTier( Hero hero ){
		if (hero.belongings.weapon() == null) return 0f;
		return Math.max( 0, hero.belongings.weapon().level() );
	}

	/** Turns remaining on a duration buff, scaled by a typical maximum. */
	private static float buffRemaining( Hero hero, Class<? extends Buff> type, float scale ){
		Buff buff = hero.buff( type );
		if (buff == null) return 0f;
		return Math.min( 1f, Math.max( 0f, buff.cooldown() ) / scale );
	}
}