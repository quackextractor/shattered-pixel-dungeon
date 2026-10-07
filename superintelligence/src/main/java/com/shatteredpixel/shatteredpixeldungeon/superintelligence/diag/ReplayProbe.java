package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reports what the update's replay of a sampled observation depends on.
 *
 * <pre>gradle :superintelligence:replayprobe</pre>
 *
 * Not a gate. It exists to put numbers on a specific question, because the answer decides how the
 * parallel update has to be built:
 *
 * <p><b>Does replaying a stored observation reproduce the value the rollout saw?</b> {@code PPO.update}
 * shuffles the buffer and then calls {@code network.forward} once per sample, carrying the LSTM's
 * {@code h}/{@code c} across samples. Two consequences follow, and only one of them is obvious:
 *
 * <ol>
 *   <li>The hidden state a sample is replayed under is whatever the *previously processed* sample left
 *       behind — a different timestep of a different episode. So the value and logits the update
 *       computes are not the ones the behaviour policy acted on, and {@code oldLogProbability} is
 *       compared against a ratio produced under the wrong state.
 *   <li>It also means samples are <em>coupled</em>: sample <i>i</i>'s result depends on sample
 *       <i>i-1</i>. That is the reason a parallel update is not a thread pool over minibatches, and
 *       it is invisible from the code alone.
 * </ol>
 *
 * <p>This prints, for a set of synthetic transitions: the rollout value, the replayed value under a
 * correct state, and the replayed value under a stale one. If the first two agree and the third does
 * not, then the state has to travel with the transition — which decides the fix.
 */
public class ReplayProbe {

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();
		//small, because this is about arithmetic rather than scale
		config.gridWidth = 12;
		config.gridHeight = 12;
		config.maxSlots = 4;

		Network network = new Network( config, new Random( 31337L ) );
		Random rng = new Random( 4242L );

		int n = 12;
		List<Transition> transitions = new ArrayList<>();
		float[] scratch = new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ];

		//collected sequentially, the way a rollout is: forward, then read the value, carrying state
		for (int i = 0; i < n; i++){
			Transition t = new Transition();
			t.grid = new byte[ scratch.length ];
			t.inventory = new float[ config.maxSlots
					* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES ];
			t.actionMask = new float[ Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ ActionMapper.TARGET_COUNT ];

			for (int g = 0; g < t.grid.length; g++) t.grid[ g ] = (byte) (rng.nextFloat() > 0.6f ? 1 : 0);
			for (float[] a : new float[][]{ t.inventory, t.hero, t.actionMask, t.slotMask, t.targetMask }){
				for (int k = 0; k < a.length; k++) a[ k ] = rng.nextFloat();
			}
			t.liveHead = Policy.HEAD_ACTION;
			t.actionIndex = 3;
			t.oldLogProbability = -1.1f;

			//the rollout's forward, carrying h/c
			network.forward( scratchFrom( t, scratch ), t.inventory, t.hero );
			t.value = network.value();

			transitions.add( t );
		}

		//What the update sees. Same network, same weights, but the state under which each sample is
		//replayed is the one left by the previous sample of the shuffled buffer.
		double correctDrift = 0;
		double staleDrift = 0;
		double worstCorrect = 0;
		double worstStale = 0;

		//replay each sample under the state the rollout had at that point
		network.resetState();
		for (int i = 0; i < n; i++){
			Transition t = transitions.get( i );
			network.forward( scratchFrom( t, scratch ), t.inventory, t.hero );
			double d = Math.abs( network.value() - t.value );
			correctDrift += d;
			worstCorrect = Math.max( worstCorrect, d );
		}

		//replay the same samples in reverse, so each is preceded by a different one
		network.resetState();
		for (int i = n - 1; i >= 0; i--){
			Transition t = transitions.get( i );
			network.forward( scratchFrom( t, scratch ), t.inventory, t.hero );
			double d = Math.abs( network.value() - t.value );
			staleDrift += d;
			worstStale = Math.max( worstStale, d );
		}

		System.out.println( "samples      " + n + ", value head output width " + valueRange( network ) );
		System.out.println();
		System.out.println( "replayed in collection order (state carries from the true predecessor):" );
		System.out.println( "  mean |replayed - rollout| " + fmt( correctDrift / n ) );
		System.out.println( "  max  |replayed - rollout| " + fmt( worstCorrect ) );
		System.out.println();
		System.out.println( "replayed in reverse (state carries from an unrelated sample):" );
		System.out.println( "  mean |replayed - rollout| " + fmt( staleDrift / n ) );
		System.out.println( "  max  |replayed - rollout| " + fmt( worstStale ) );
		System.out.println();
		System.out.println( "The first row is the value the behaviour policy actually saw. The second is" );
		System.out.println( "what PPO.update computes, because it shuffles: a sample is replayed under the" );
		System.out.println( "hidden state left by whichever sample happened to precede it." );
	}

	private static float[] scratchFrom( Transition t, float[] dst ){
		t.unpackGrid( dst );
		return dst;
	}

	private static String valueRange( Network network ){
		network.resetState();
		return "1";
	}

	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.6f", v );
	}
}
