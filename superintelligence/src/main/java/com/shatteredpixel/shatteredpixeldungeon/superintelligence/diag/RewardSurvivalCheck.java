package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;

import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardSurvivalCheck {

	private RewardSurvivalCheck(){}

	public static void checkEatingBeforeHungerIsPaidFor( EnvConfig config ){
		final int[] eaten = { -1 };

		Outcome o = play( config, 8, "EATEARLY", turn -> {
			SPDEnv env = turn.env;
			if (turn.step == 1){
				env.step( Action.OPEN_INVENTORY, 0 );
				return;
			}
			if (turn.step == 2 && env.mode() == EnvMode.INVENTORY){
				int before = Statistics.foodEaten;
				env.step( Action.USE, slotOf( env, Food.class ) );
				eaten[ 0 ] = Statistics.foodEaten - before;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (eaten[ 0 ] != 1){
			fail( "the hero was asked to eat his ration from the inventory and ate " + eaten[ 0 ]
					+ " time(s), so this case measured nothing." );
			return;
		}

		double expected = config.eatenEarlyPenalty;
		if (o.notes( RewardTerm.EATEN_EARLY ) != 1
				|| Math.abs( o.termTotal( RewardTerm.EATEN_EARLY ) - expected ) > 1e-6){
			fail( "the hero ate a ration on a full stomach and EATEN_EARLY fired "
					+ o.notes( RewardTerm.EATEN_EARLY ) + " time(s) paying "
					+ fmt( o.termTotal( RewardTerm.EATEN_EARLY ) ) + " where once at " + fmt( expected )
					+ " was expected. Hunger.level rises with time and eating lowers it, so a rise is"
					+ " time passing, not a meal." );
			return;
		}

		System.out.println( "  eating before hunger pays EATEN_EARLY once: "
				+ fmt( o.termTotal( RewardTerm.EATEN_EARLY ) ) );
	}


	public static void checkALevelUpPaysItsHealthOnce( EnvConfig config ){
		final int[] grew = { 0 };
		final double[] paid = { 0 };

		play( config, 4, "LEVELHP", turn -> {
			SPDEnv env = turn.env;
			if (turn.step == 1){
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero =
						com.shatteredpixel.shatteredpixeldungeon.Dungeon.hero;
				int before = hero.HT;
				double already = env.ledger().termTotal( RewardTerm.MAX_HP_GAIN );
				hero.earnExp( hero.maxExp(), RewardSurvivalCheck.class );
				env.step( Action.WAIT, 0 );
				grew[ 0 ] = hero.HT - before;
				paid[ 0 ] = env.ledger().termTotal( RewardTerm.MAX_HP_GAIN ) - already;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (grew[ 0 ] <= 0){
			fail( "the hero was given a level's worth of experience and his maximum health moved by "
					+ grew[ 0 ] + ", so this case measured nothing." );
			return;
		}

		double expected = config.maxHpReward * 5f * grew[ 0 ];
		if (Math.abs( paid[ 0 ] - expected ) > 1e-6){
			fail( "a level-up raised maximum health by " + grew[ 0 ] + " and MAX_HP_GAIN paid "
					+ fmt( paid[ 0 ] ) + " where " + fmt( expected ) + " was expected. Hero.updateHT(true)"
					+ " raises HP with HT, so paying the HP rise as healing counts the same points twice." );
			return;
		}

		System.out.println( "  a level-up that adds " + grew[ 0 ] + " maximum health pays MAX_HP_GAIN "
				+ fmt( paid[ 0 ] ) + " once" );
	}


	static int slotOf( SPDEnv env, Class< ? extends Item > type ){
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (type.isInstance( slots.get( i ) )) return i;
		}
		return 0;
	}
}
