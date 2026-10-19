package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

import java.util.Random;
import java.util.function.Consumer;

final class PolicyUpdateCheck {
	private PolicyUpdateCheck() {}
	static void run( Consumer< String > fail ){
		checkAnUpdateMovesTheChosenProbabilityWithTheAdvantage( fail );
		checkTheClipOnlyStopsTheSideItBounds( fail );
	}
	private static void checkAnUpdateMovesTheChosenProbabilityWithTheAdvantage( Consumer< String > fail ){
		float[] up = chosenProbabilityAcrossOneUpdate( 1f );
		float[] down = chosenProbabilityAcrossOneUpdate( -1f );
		if (!(up[ 1 ] > up[ 0 ])){
			fail.accept( "one update at advantage +1 moved p(chosen) from " + up[ 0 ] + " to " + up[ 1 ]
					+ "; a rewarded action has to become more likely" );
		}
		if (!(down[ 1 ] < down[ 0 ])){
			fail.accept( "one update at advantage -1 moved p(chosen) from " + down[ 0 ] + " to " + down[ 1 ]
					+ "; a punished action has to become less likely" );
		}
		System.out.println( "  one update moves p(chosen) " + up[ 0 ] + " -> " + up[ 1 ] + " at A=+1, "
				+ down[ 0 ] + " -> " + down[ 1 ] + " at A=-1" );
	}
	private static float[] chosenProbabilityAcrossOneUpdate( float advantage ){
		EnvConfig config = new EnvConfig();
		Network net = new Network( config, new Random( 777L ));
		float[] grid = new float[ net.gridLength() ];
		float[] inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
		float[] hero = new float[ HeroEncoder.FEATURES ];
		Random rng = new Random( 778L );
		for (int i = 0; i < grid.length; i++) grid[ i ] = rng.nextInt( 5 ) == 0 ? 1f : 0f;
		for (int i = 0; i < hero.length; i++) hero[ i ] = rng.nextFloat();
		float[] mask = new float[ net.actionCount() ];
		java.util.Arrays.fill( mask, 1f );
		float[] probabilities = new float[ mask.length ];
		int chosen = 3;
		net.resetState();
		net.forward( grid, inventory, hero );
		Policy.probabilities( net.actionLogits(), mask, probabilities );
		float before = probabilities[ chosen ];
		float[] gradient = new float[ mask.length ];
		Policy.accumulatePolicyGradient( probabilities, mask, chosen, Policy.logProbability( probabilities, chosen ),
				advantage, 0.2f, 0f, gradient );
		net.zeroGrad();
		net.backward( gradient, null, null, 0f );
		net.step( 1f );
		net.resetState();
		net.forward( grid, inventory, hero );
		Policy.probabilities( net.actionLogits(), mask, probabilities );
		return new float[]{ before, probabilities[ chosen ] };
	}
	private static void checkTheClipOnlyStopsTheSideItBounds( Consumer< String > fail ){
		float[][] cases = { { 1f, 0.5f, 1f }, { 1f, 1.5f, 0f }, { -1f, 0.5f, 0f }, { -1f, 1.5f, 1f } };
		for (float[] c : cases){
			boolean moves = gradientNormAt( c[ 0 ], c[ 1 ] ) > 0f;
			if (moves != (c[ 2 ] > 0f)){
				fail.accept( "advantage " + c[ 0 ] + " at ratio " + c[ 1 ] + (moves ? " still has" : " has no")
						+ " policy gradient; the clip may only stop a ratio that has already moved past"
						+ " 1 +/- epsilon in the advantage's own direction" );
			}
		}
	}
	private static float gradientNormAt( float advantage, float ratio ){
		float[] probabilities = { 0.5f, 0.3f, 0.15f, 0.05f };
		float[] mask = { 1f, 1f, 1f, 1f };
		float oldLogProbability = (float) Math.log( probabilities[ 0 ] ) - (float) Math.log( ratio );
		float[] gradient = new float[ probabilities.length ];
		Policy.accumulatePolicyGradient( probabilities, mask, 0, oldLogProbability, advantage, 0.2f, 0f, gradient );
		float sum = 0f;
		for (float g : gradient) sum += Math.abs( g );
		return sum;
	}
}
