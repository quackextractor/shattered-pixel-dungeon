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


	static int slotOf( SPDEnv env, Class< ? extends Item > type ){
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (type.isInstance( slots.get( i ) )) return i;
		}
		return 0;
	}
}
