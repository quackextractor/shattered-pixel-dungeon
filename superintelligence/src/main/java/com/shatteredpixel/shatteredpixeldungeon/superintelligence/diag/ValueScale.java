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
 * <p>The question: {@code Network.valueHead} is a {@code Dense}, which applies {@code tanh}, so the
 * critic's output is confined to {@code [-1, +1]}. Meanwhile {@code EnvConfig.deathPenalty} is 100 and
 * {@code victoryReward} is 1000. If the GAE return is left unnormalised — and it is; only
 * {@code advantage} is normalised — then the critic is being asked to fit targets it cannot represent,
 * and the squared error is dominated by a handful of samples rather than spread across the batch.
 *
 * <p>That would show up as a {@code valueLoss} that rises and then sits there. Measured on a 12-
 * generation run it did: 2.97 to 8.11, non-monotonic but clearly not falling.
 *
 * <p>What this prints: the return targets' min, max and percentiles, and the head's reachable range.
 */
public class ValueScale {

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		//the real reward constants, so the numbers here are the ones a run will see
		System.out.println( "env: depthReward=" + config.depthReward
				+ " deathPenalty=" + config.deathPenalty
				+ " victoryReward=" + config.victoryReward );
		System.out.println();

		Network network = new Network( config, new Random( 5L ) );
		PPO ppo = new PPO( config, new Random( 5L ) );

		//synthetic targets spanning the plausible range a rollout produces: ordinary steps, a truncated
		//episode, and the two large terms
		float[] targets = {
				0.02f, -0.01f, 0.05f, 0.11f, 0.04f, -0.02f, 0.03f, 12.4f, 8.1f, -3.2f,
				-100f, 250f, 0.07f, -0.04f, 0.9f
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
		System.out.println( "  mean of target^2 (the loss a perfect critic would reach 0 from) "
				+ fmt( sumSq / sorted.length ) );
		System.out.println();

		//the head's reachable range, empirically: drive it hard and see where it saturates
		network.resetState();
		float observed = 0;
		for (int i = 0; i < 8; i++){
			network.forward( new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ],
					new float[ config.maxSlots
					* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT ],
					new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES ] );
			observed = network.value();
		}

		System.out.println( "value head: Dense, so tanh. Reachable output is [-1, +1]." );
		System.out.println( "  observed output " + fmt( observed ) );
		System.out.println();

		System.out.println( "The largest target is " + fmt( Math.abs( sorted[ sorted.length - 1 ] ) )
				+ "x the head's maximum reach." );
		System.out.println( "A target the head cannot reach contributes a squared error that no amount" );
		System.out.println( "of training removes, so valueLoss floors out instead of falling to zero." );
		System.out.println();
		System.out.println( "  three ways out, in order of how much they change:" );
		System.out.println( "    1. make the value head linear - it is a regression, not a classifier" );
		System.out.println( "    2. normalise the return targets as well as the advantages" );
		System.out.println( "    3. scale the reward down so a death is not 100x a turn" );
		System.out.println( "  (1) is the smallest and fixes the cause; (2) and (3) reshape the problem." );
	}

	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.4f", v );
	}
}
