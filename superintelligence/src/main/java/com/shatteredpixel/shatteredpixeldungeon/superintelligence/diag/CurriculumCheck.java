package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.Curriculum;

import java.io.File;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.descend;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public class CurriculumCheck {

	private static final int CHECKS = 5;

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-curriculumcheck" ));
		HeadlessServices.disableSaving( true );

		checkTheScaleIsOneBelowTheFadeAndZeroAboveIt();
		checkTheScaleNeverRisesWithDepth();
		checkTheAggregationIsTheMeanOfThePerEpisodeScale();
		checkAFloorChangeMovesTheObservation();
		checkAFloorChangeReachesTheLedger();

		System.exit( GateReport.report( "curriculum", CHECKS ));
	}


	private static void fail( String message ){
		GateReport.fail( message );
	}


	private static void checkTheScaleIsOneBelowTheFadeAndZeroAboveIt(){
		Curriculum curriculum = new Curriculum();

		for (int depth = 1; depth <= 40; depth++){
			float scale = curriculum.scaleForDepth( depth );

			if (depth <= curriculum.fadeStartDepth() && scale != 1f){
				fail( "depth " + depth + " is at or below the fade start of "
						+ curriculum.fadeStartDepth() + " and scales shaping to " + scale
						+ ", so the micro rewards are already faded for an agent that has not left"
						+ " floor 3" );
				return;
			}
			if (depth >= curriculum.fadeEndDepth() && scale != 0f){
				fail( "depth " + depth + " is at or beyond the fade end of " + curriculum.fadeEndDepth()
						+ " and scales shaping to " + scale + ", so the micro rewards never stop" );
				return;
			}
			if (depth > curriculum.fadeStartDepth() && depth < curriculum.fadeEndDepth()
					&& !( scale > 0f && scale < 1f )){
				fail( "depth " + depth + " is inside the fade and scales shaping to " + scale
						+ ". research.md's phase 2 fades the micro rewards out and phase 1 pays them;"
						+ " a scale of 0 or 1 in the middle of the fade is one of those and not both" );
				return;
			}
		}

		System.out.println( "  shaping is 1.0 up to depth " + curriculum.fadeStartDepth()
				+ ", 0.0 from depth " + curriculum.fadeEndDepth() + ", and strictly between them in between" );
	}


	private static void checkTheScaleNeverRisesWithDepth(){
		Curriculum curriculum = new Curriculum();

		float previous = curriculum.scaleForDepth( 1 );
		for (int depth = 2; depth <= 40; depth++){
			float scale = curriculum.scaleForDepth( depth );
			if (scale > previous){
				fail( "depth " + depth + " scales shaping to " + scale + " against " + previous
						+ " at the floor above, so a deeper agent is paid *more* for the micro"
						+ " behaviours the curriculum is fading out" );
				return;
			}
			previous = scale;
		}

		System.out.println( "  the fade is monotone: every deeper floor scales shaping no higher" );
	}


	private static void checkTheAggregationIsTheMeanOfThePerEpisodeScale(){
		Curriculum curriculum = new Curriculum( 2f, 6f );

		int[] depths = { 1, 2, 3, 7, 12 };
		float expected = 0f;
		for (int depth : depths) expected += curriculum.scaleForDepth( depth );
		expected /= depths.length;

		float actual = curriculum.meanScale( depths );

		if (Math.abs( actual - expected ) > 1e-6f ){
			fail( "a generation's shaping reports " + actual + " where the mean of its episodes'"
					+ " own scales is " + expected + ". The number under 'shaping=' has to be the one"
					+ " that priced the run, or it is a constant with a label on it" );
			return;
		}

		if (curriculum.meanScale( new int[ 0 ]) != 1f){
			fail( "a generation with no episodes reports a shaping scale other than 1" );
			return;
		}

		if (curriculum.meanScale( new int[]{ 1, 1 }) != 1f){
			fail( "two episodes that never left floor 1 report a shaping scale other than 1" );
			return;
		}

		System.out.println( "  a generation's shaping is the mean of the per-episode scales: "
				+ String.format( java.util.Locale.ROOT, "%.4f", actual ) + " over depths "
				+ java.util.Arrays.toString( depths ));
	}


	private static void checkAFloorChangeMovesTheObservation(){
		final int[] deepest = { 0 };
		final int[] curriculumDeepest = { 0 };
		final float[] scaleAtDeepest = { -1f };
		final boolean[] reachedWorld = { false };

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 400;

		play( config, 300, "CURRICULUM-A", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}
			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) return;

			reachedWorld[ 0 ] = true;
			deepest[ 0 ] = env.deepestDepth();
			curriculumDeepest[ 0 ] = env.curriculum().deepestSeen();
			scaleAtDeepest[ 0 ] = env.curriculum().shapingScale();

			if (turn.step % 12 == 0 && deepest[ 0 ] < 6 && descend( env )) return;
			env.step( Action.WAIT, 0 );
		} );

		if (!reachedWorld[ 0 ]){
			fail( "the descent fixture never reached a WORLD turn, so this case measured nothing" );
			return;
		}
		if (deepest[ 0 ] < 6){
			fail( "the driver reached depth " + deepest[ 0 ] + " and not 6, so it never crossed the"
					+ " fade start of 3 and the curriculum's own scale could not have moved" );
			return;
		}
		if (curriculumDeepest[ 0 ] < 6){
			fail( "the environment descended to depth " + deepest[ 0 ] + " and the curriculum's"
					+ " deepestSeen is " + curriculumDeepest[ 0 ] + ", so Curriculum.observe() is not"
					+ " being called on a floor change and shapingScale() is the constant 1f it has"
					+ " been reported to be" );
			return;
		}
		if (scaleAtDeepest[ 0 ] >= 1f ){
			fail( "the run reached depth " + deepest[ 0 ] + " and the shaping scale was still "
					+ scaleAtDeepest[ 0 ] + ", so deepestSeen moved and the scale did not" );
			return;
		}

		System.out.println( "  descending past the fade start moves the scale: deepest floor "
				+ deepest[ 0 ] + ", shaping " + String.format( java.util.Locale.ROOT, "%.4f", scaleAtDeepest[ 0 ] ) );
	}


	private static void checkAFloorChangeReachesTheLedger(){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 400;

		final float[] ledgerScale = { -1f };
		final int[] depth = { 0 };
		final SPDEnv[] used = { null };

		play( config, 300, "CURRICULUM-B", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}
			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) return;

			used[ 0 ] = env;
			if (turn.step % 12 == 0 && env.deepestDepth() < 6 && descend( env )) return;

			depth[ 0 ] = env.deepestDepth();
			ledgerScale[ 0 ] = (float) env.ledger().shapingScale();
			env.step( Action.WAIT, 0 );
		} );

		if (ledgerScale[ 0 ] < 0f ){
			fail( "the driver never descended, so the ledger's shaping scale was never sampled and"
					+ " this case measured nothing" );
			return;
		}
		if (depth[ 0 ] <= 3 ){
			fail( "the driver reached depth " + depth[ 0 ] + ", so it never crossed the fade start of 3"
					+ " and the ledger's shaping scale could not have moved. This case measured nothing." );
			return;
		}
		if (ledgerScale[ 0 ] >= 1f ){
			fail( "after descending to depth " + depth[ 0 ] + " the ledger still priced every shaping"
					+ " term at " + ledgerScale[ 0 ] + ". RewardLedger multiplies by shapingScale, so a"
					+ " scale that never leaves 1f means the fade is written, wired, documented and"
					+ " inert" );
			return;
		}

		used[ 0 ].reset( "CURRICULUM-B", HeroClass.WARRIOR );
		if (used[ 0 ].ledger().shapingScale() != 1.0){
			fail( "a new episode on floor 1 prices shaping at " + used[ 0 ].ledger().shapingScale()
					+ ", the scale the previous episode left at depth " + depth[ 0 ] + ". The curriculum"
					+ " is reset and the ledger was not, so every episode after a deep one is under-paid"
					+ " until it changes floor" );
			return;
		}

		System.out.println( "  the ledger's shaping scale follows the floor: "
				+ String.format( java.util.Locale.ROOT, "%.4f", ledgerScale[ 0 ] )
				+ " on a run that reached depth " + depth[ 0 ] + ", and 1 again after a reset" );
	}
}