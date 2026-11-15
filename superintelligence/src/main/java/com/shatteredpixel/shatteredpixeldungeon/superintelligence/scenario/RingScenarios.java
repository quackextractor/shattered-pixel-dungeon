package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfArcana;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfElements;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfEnergy;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfEvasion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfForce;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfFuror;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfHaste;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfSharpshooting;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfTenacity;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfWealth;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.USE;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.effect;

final class RingScenarios {

	private RingScenarios(){}

	private static final int LEVEL = 3;

	private static final long DAMAGE_SAMPLE_SEED = 11L;

	private static final int DAMAGE_SAMPLES = 400;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( ring( RingOfArcana.class, 1, () -> RingOfArcana.enchantPowerMultiplier( Dungeon.hero )));
		all.add( ring( RingOfElements.class, -1, () -> RingOfElements.resist( Dungeon.hero, Burning.class )));
		all.add( ring( RingOfEnergy.class, 1, () -> RingOfEnergy.wandChargeMultiplier( Dungeon.hero )));
		all.add( ring( RingOfEvasion.class, 1, () -> RingOfEvasion.evasionMultiplier( Dungeon.hero )));
		all.add( force() );
		all.add( ring( RingOfFuror.class, 1, () -> RingOfFuror.attackSpeedMultiplier( Dungeon.hero )));
		all.add( ring( RingOfHaste.class, 1, () -> RingOfHaste.speedMultiplier( Dungeon.hero )));
		all.add( ring( RingOfMight.class, 1, () -> RingOfMight.strengthBonus( Dungeon.hero )));
		all.add( ring( RingOfSharpshooting.class, 1, () -> RingOfSharpshooting.levelDamageBonus( Dungeon.hero )));
		all.add( ring( RingOfTenacity.class, -1, ( world, kit ) -> kit.health( 500, 1000 ), () -> RingOfTenacity.damageMultiplier( Dungeon.hero )));
		all.add( ring( RingOfWealth.class, 1, () -> RingOfWealth.dropChanceMultiplier( Dungeon.hero )));
		return all;
	}

	private static Scenario ring( Class< ? extends Ring > type, int sign, DoubleSupplier getter ){
		return ring( type, sign, ( world, kit ) -> {}, getter );
	}

	private static Scenario ring( Class< ? extends Ring > type, int sign, Scenario.Setup arena, DoubleSupplier getter ){
		String name = type.getSimpleName();
		return effect( "ring." + name.substring( "RingOf".length() ), type, USE, ( world, kit ) -> {
			kit.hero().belongings.getItem( type ).upgrade( LEVEL );
			arena.build( world, kit );
		}, 0, "equipping the +" + LEVEL + " " + name + " left its getter " + (sign > 0 ? "unraised" : "unlowered"), () -> {
			Ring ring = Dungeon.hero.belongings.getItem( type );
			double before = getter.getAsDouble();
			return () -> ring.isEquipped( Dungeon.hero ) && sign * (getter.getAsDouble() - before) > 0;
		}).covers();
	}

	private static Scenario force(){
		return effect( "ring.Force", RingOfForce.class, USE, ( world, kit ) -> {
			kit.hero().belongings.getItem( RingOfForce.class ).upgrade( LEVEL );
			kit.hero().belongings.weapon = null;
		}, 0, "equipping the +" + LEVEL + " RingOfForce raised neither its bonus nor the unarmed mean damage by 1", () -> {
			Ring ring = Dungeon.hero.belongings.getItem( RingOfForce.class );
			int bonus = RingOfForce.armedDamageBonus( Dungeon.hero );
			float damage = meanDamage();
			return () -> ring.isEquipped( Dungeon.hero ) && RingOfForce.armedDamageBonus( Dungeon.hero ) > bonus && meanDamage() > damage + 1f;
		}).covers();
	}

	private static float meanDamage(){
		Random.pushGenerator( DAMAGE_SAMPLE_SEED );
		try {
			int total = 0;
			for (int i = 0; i < DAMAGE_SAMPLES; i++) total += Dungeon.hero.damageRoll();
			return total / (float) DAMAGE_SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}
}
