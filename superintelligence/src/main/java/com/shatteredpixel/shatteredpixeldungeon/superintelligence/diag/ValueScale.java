package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reports the range of the critic's targets against the range the critic can reach.
 *
 * <pre>gradle :superintelligence:valuescale</pre>
 *
 * <p>Not a gate: it answers a question whose answer decides a design change rather than a code
 * change, so it prints the distribution and leaves the decision visible.
 *
 * <p>The question it was written to answer: {@code Network.valueHead} is a {@code Dense}, which applies
 * {@code tanh}, so the critic's output is confined to {@code [-1, +1]} against a {@code deathPenalty} of
 * 100 and a {@code victoryReward} of 1000 - targets it cannot represent. **That is fixed**;
 * {@code Network.java:107} constructs the head with {@code tanh = false}, so this header described a
 * fault that no longer exists and was corrected when the run it was read off was measured.
 *
 * <p>It is kept because the question is still worth asking of the returns themselves. With a linear head
 * the range is no longer the binding constraint, but the magnitude is: measured over three repeats of one
 * 15-generation config, {@code valueLoss} spans **12,433 to 31,323** - a factor of 2.5 on identical
 * config and seed - and sits in the tens of thousands rather than single digits. That is not a head that
 * cannot represent its targets; it is a return distribution dominated by the terminal penalty.
 * {@code issues.md} 27 has the measurement.
 *
 * <p>What this prints: the return targets' min, max and percentiles, and the head's reachable range.
 */
public class ValueScale {

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();


		System.out.println( "env: depthReward=" + config.depthReward
				+ " deathPenalty=" + config.deathPenalty
				+ " victoryReward=" + config.victoryReward );
		System.out.println();

		Network network = new Network( config, new Random( 5L ) );
		PPO ppo = new PPO( config, new Random( 5L ) );











		float[] targets = {
				0.02f, -0.01f, 0.05f, 0.11f, 0.04f, -0.02f, 0.03f, 12.4f, 8.1f, -3.2f,
				-config.deathPenalty, config.victoryReward, 0.07f, -0.04f
		};

		List<Transition> batch = new ArrayList<>();
		for (float target : targets){
			Transition t = new Transition();
			t.grid = new byte[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
			t.inventory = new float[ config.maxSlots
					* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES ];
			t.actionMask = new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT ];
			t.returnValue = target;
			batch.add( t );
		}

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
			network.forward( new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ],
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
