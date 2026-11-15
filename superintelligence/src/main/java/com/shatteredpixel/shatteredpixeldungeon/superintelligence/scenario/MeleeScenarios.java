package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.AssassinsBlade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.BattleAxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Crossbow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Cudgel;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dirk;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Flail;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gauntlet;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Glaive;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gloves;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greataxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatshield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.HandAxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Katana;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Mace;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Quarterstaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Rapier;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RoundShield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RunicBlade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Scimitar;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Shortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sickle;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Spear;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WarHammer;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WarScythe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Whip;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;

final class MeleeScenarios {

	private MeleeScenarios(){}

	private static final int ATTACKS = 6;

	static final List< Class< ? extends MeleeWeapon > > WEAPONS = List.of( AssassinsBlade.class, BattleAxe.class, Crossbow.class,
			Cudgel.class, Dirk.class, Flail.class, Gauntlet.class, Glaive.class, Gloves.class, Greataxe.class, Greatshield.class,
			Greatsword.class, HandAxe.class, Katana.class, Longsword.class, Mace.class, Quarterstaff.class, Rapier.class,
			RoundShield.class, RunicBlade.class, Sai.class, Scimitar.class, Shortsword.class, Sickle.class, Spear.class,
			WarHammer.class, WarScythe.class, Whip.class, WornShortsword.class );

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		for (Class< ? extends MeleeWeapon > type : WEAPONS) all.add( wield( type ));
		return all;
	}

	private static Scenario wield( Class< ? extends MeleeWeapon > type ){
		String name = type.getSimpleName();
		return new Scenario( "weapon." + name, "equip " + name + " and hit a rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 20 );
					kit.hero().belongings.weapon = null;
					kit.give( Reflection.newInstance( type ));
					Rat rat = world.hunting( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 100;
				},
				d -> {
					d.use( type );
					d.require( type.isInstance( Dungeon.hero.belongings.weapon ), "using the " + name + " left the hero wielding "
							+ Dungeon.hero.belongings.weapon );
					Rat rat = d.world().find( Rat.class );
					int hp = rat.HP;
					d.expect( d.until( () -> !rat.isAlive() || rat.HP < hp, ATTACKS, () -> d.move( rat.pos )),
							"the " + name + " left the rat at " + hp + " HP after " + ATTACKS + " attacks" );
				} ).covers( type );
	}
}
