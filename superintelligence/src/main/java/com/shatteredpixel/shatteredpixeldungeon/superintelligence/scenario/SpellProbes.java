package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.watabou.utils.Random;

import java.util.function.IntSupplier;

final class SpellProbes {

	private SpellProbes(){}

	private static final int HIT = 10;

	private static final int DUMMY_HP = 100;

	private static final long SEED = 7L;

	private static final class Dummy extends Char {

		Dummy( Alignment side, int cell ){
			alignment = side;
			pos = cell;
			HP = HT = DUMMY_HP;
		}

		@Override
		protected boolean act(){
			return true;
		}

		int taken(){
			return HT - HP;
		}
	}

	static int armourBlocks(){
		Hero hero = Dungeon.hero;
		return sampled( () -> HIT - hero.belongings.armor().proc( hero, hero, HIT ));
	}

	static int shieldCut( Char attacker ){
		return sampled( () -> HIT - Dungeon.hero.defenseProc( attacker, HIT ));
	}

	static int auraTaken(){
		return sampled( () -> {
			Dummy ally = new Dummy( Char.Alignment.ALLY, Dungeon.hero.pos );
			ally.damage( HIT, new Object() );
			return ally.taken();
		});
	}

	static int weaponBonus(){
		return sampled( () -> {
			Dummy foe = new Dummy( Char.Alignment.ENEMY, Dungeon.hero.pos );
			Dungeon.hero.belongings.weapon().proc( Dungeon.hero, foe, 0 );
			return foe.taken();
		});
	}

	private static int sampled( IntSupplier probe ){
		Random.pushGenerator( SEED );
		try {
			return probe.getAsInt();
		} finally {
			Random.popGenerator();
		}
	}
}
