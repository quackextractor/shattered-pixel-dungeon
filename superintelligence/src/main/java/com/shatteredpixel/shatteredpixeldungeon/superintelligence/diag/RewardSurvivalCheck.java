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


	public static void checkOnlyIdentifyingSomethingCarriedPays( EnvConfig config ){
		final double[] onPickup = { Double.NaN };
		final double[] onIdentify = { Double.NaN };
		final Item[] ring = { null };

		play( config, 8, "IDENTIFY", turn -> {
			SPDEnv env = turn.env;
			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero =
					com.shatteredpixel.shatteredpixeldungeon.Dungeon.hero;
			double before = env.ledger().termTotal( RewardTerm.IDENTIFIED );
			if (turn.step == 1){
				com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.drop(
						new com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword().identify(),
						hero.pos );
				env.step( Action.INTERACT, 0 );
				onPickup[ 0 ] = env.ledger().termTotal( RewardTerm.IDENTIFIED ) - before;
				return;
			}
			if (turn.step == 2){
				ring[ 0 ] = new com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfAccuracy();
				ring[ 0 ].collect( hero.belongings.backpack );
				env.step( Action.WAIT, 0 );
				return;
			}
			if (turn.step == 3){
				ring[ 0 ].identify();
				env.step( Action.WAIT, 0 );
				onIdentify[ 0 ] = env.ledger().termTotal( RewardTerm.IDENTIFIED ) - before;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (Double.isNaN( onPickup[ 0 ] ) || Double.isNaN( onIdentify[ 0 ] )){
			fail( "the driver never reached both the pickup and the identification, so this case"
					+ " measured nothing." );
			return;
		}
		if (onPickup[ 0 ] != 0){
			fail( "picking up a sword that was already identified paid " + fmt( onPickup[ 0 ] )
					+ " of IDENTIFIED. Nothing was learned; the carried count went up by one." );
			return;
		}
		if (Math.abs( onIdentify[ 0 ] - config.identifiedReward ) > 1e-6){
			fail( "identifying a carried ring paid " + fmt( onIdentify[ 0 ] ) + " of IDENTIFIED where "
					+ fmt( config.identifiedReward ) + " was expected." );
			return;
		}

		System.out.println( "  IDENTIFIED pays " + fmt( onIdentify[ 0 ] ) + " for identifying a carried"
				+ " ring and nothing for picking up a known sword" );
	}


	public static void checkTheFirstStepIsScored( EnvConfig config ){
		Outcome o = play( config, 3, "FIRSTSTEP", turn -> {
			if (turn.step == 0){
				com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.drop(
						new com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing(),
						com.shatteredpixel.shatteredpixeldungeon.Dungeon.hero.pos );
				turn.env.step( Action.INTERACT, 0 );
				return;
			}
			turn.env.step( Action.WAIT, 0 );
		} );

		if (o.notes( RewardTerm.ITEM_PICKUP ) != 1){
			fail( "a potion picked up on the episode's first step noted ITEM_PICKUP "
					+ o.notes( RewardTerm.ITEM_PICKUP ) + " time(s). SPDEnv.reset invalidated the reward"
					+ " snapshot after settling, so the first step only re-took it and every diff on it"
					+ " was lost." );
			return;
		}

		System.out.println( "  the first step of an episode is scored: a pickup on it pays "
				+ fmt( o.termTotal( RewardTerm.ITEM_PICKUP ) ) );
	}


	static int slotOf( SPDEnv env, Class< ? extends Item > type ){
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (type.isInstance( slots.get( i ) )) return i;
		}
		return 0;
	}
}
