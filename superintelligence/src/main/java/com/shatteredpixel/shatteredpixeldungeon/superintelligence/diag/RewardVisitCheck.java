package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardVisitCheck {

	private RewardVisitCheck(){}

	public static void checkOnlyANewCellIsPaidFor( EnvConfig config ){
		Outcome waits = RewardFixtures.playWaits( config, 20 );
		Action[] first = new Action[ 1 ];
		Outcome pacing = play( config, 20, "REWARDCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}
			if (first[ 0 ] == null) first[ 0 ] = RewardFixtures.legalMove( turn.env );
			turn.env.step( turn.step % 2 == 0 ? first[ 0 ] : opposite( first[ 0 ] ), 0 );
		} );
		double one = config.visitReward;
		if (Math.abs( waits.termTotal( RewardTerm.VISIT ) - one ) > 1e-6
				|| Math.abs( pacing.termTotal( RewardTerm.VISIT ) - 2 * one ) > 1e-6){
			fail( "VISIT paid " + fmt( waits.termTotal( RewardTerm.VISIT ) ) + " for standing still and "
					+ fmt( pacing.termTotal( RewardTerm.VISIT ) ) + " for pacing between two cells, against "
					+ fmt( one ) + " and " + fmt( 2 * one ) + ". A cell must pay once per floor, or pacing farms it." );
			return;
		}
		System.out.println( "  standing still pays VISIT once, pacing between two cells pays it twice" );
	}

	public static void checkVisitingCannotOutbuyADescent( EnvConfig config ){
		final int CELLS = 600;
		double visits = config.visitReward * CELLS;
		if (visits >= config.depthReward){
			fail( "standing on " + CELLS + " cells pays " + fmt( visits ) + " against a " + fmt( config.depthReward )
					+ " descent, so walking a floor end to end outbids leaving it" );
			return;
		}
		System.out.println( "  standing on " + CELLS + " cells pays " + fmt( visits ) + " against a "
				+ fmt( config.depthReward ) + " descent" );
	}

	private static Action opposite( Action move ){
		for (Action a : Action.values()){
			if (a.directional() && a.dx == -move.dx && a.dy == -move.dy) return a;
		}
		return Action.WAIT;
	}
}
