package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.util.Random;

public class ValueScale {

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		System.out.println( "env: depthReward=" + config.depthReward
				+ " deathPenalty=" + config.deathPenalty
				+ " victoryReward=" + config.victoryReward );
		System.out.println();

		Network network = new Network( config, new Random( 5L ) );

		float[] targets = {
				0.02f, -0.01f, 0.05f, 0.11f, 0.04f, -0.02f, 0.03f, 12.4f, 8.1f, -3.2f,
				-config.deathPenalty, config.victoryReward, 0.07f, -0.04f
		};

		float[] sorted = targets.clone();
		java.util.Arrays.sort( sorted );

		System.out.println( "return targets (sorted):" );
		System.out.println( "  min      " + fmt( sorted[ 0 ] ) );
		System.out.println( "  p25      " + fmt( sorted[ sorted.length / 4 ] ) );
		System.out.println( "  median   " + fmt( sorted[ sorted.length / 2 ] ) );
		System.out.println( "  p75      " + fmt( sorted[ sorted.length * 3 / 4 ] ) );
		System.out.println( "  max      " + fmt( sorted[ sorted.length - 1 ] ) );
		System.out.println();

		double sumSq = 0;
		for (float v : sorted) sumSq += (double) v * v;
		double meanSquare = sumSq / sorted.length;
		System.out.println( "  mean of target^2 (the loss a perfect critic would reach 0 from) "
				+ fmt( meanSquare ) );
		System.out.println();

		network.resetState();
		float observed = 0;
		for (int i = 0; i < 8; i++){
			network.forward( new float[ network.gridLength() ],
					new float[ config.maxSlots
					* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT ],
					new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES ] );
			observed = network.value();
		}

		System.out.println( "value head: linear (Network builds it with tanh = false), so unbounded." );
		System.out.println( "  observed output " + fmt( observed ) );
		System.out.println();

		System.out.println( "The largest target is " + fmt( Math.abs( sorted[ sorted.length - 1 ] ) )
				+ ", and the mean of target^2 is " + fmt( meanSquare )
				+ " - so a handful of terminal samples own the critic's loss." );
		System.out.println( "A target far outside the rest contributes a squared error that crowds out the" );
		System.out.println( "batch, so valueLoss is dominated by rare endings rather than by learning." );
		System.out.println();
		System.out.println( "  three ways out, in order of how much they change:" );
		System.out.println( "    1. scale the reward down - a death is " + fmt( config.deathPenalty )
				+ " and a win " + fmt( config.victoryReward ) + ", against a turn cost of "
				+ fmt( config.turnCost ) );
		System.out.println( "    2. normalise the return targets as well as the advantages" );
		System.out.println( "    3. make the value head linear - done, and it was not the cause" );
	}

	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.4f", v );
	}
}
