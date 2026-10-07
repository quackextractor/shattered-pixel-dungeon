package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared fixtures for the update checks.
 *
 * <p>Two things that {@link ParallelCheck} needs and {@link StateCheck} already had: a batch built the
 * way a worker builds one, and a way to drive {@code PPO}'s single-sample step.
 *
 * <p>Both are here rather than in one check because duplicating them is how two checks end up testing
 * different things under the same name. In particular the batch <em>must</em> carry real recurrent
 * states: a batch of zeros would make the per-sample restore a no-op, and a check that depends on that
 * restore would pass without ever exercising it.
 */
final class ParallelFixtures {

	private ParallelFixtures() {}

	/** How many samples {@link ParallelCheck} accumulates per comparison. */
	static final int BATCH = 32;

	/** The advantage spread GAE output actually has, rather than a uniform draw. */
	private static final Random ADVANTAGE_SEED = new Random( 90210L );

	/**
	 * Transitions the way {@code EpisodeCollector} produces them: sequential forwards carrying the LSTM
	 * state, with each transition remembering the state it was decided under.
	 */
	static List< Transition > batch( Network net, EnvConfig config, int n, long seed ){
		Random rng = new Random( seed );
		List< Transition > out = new ArrayList<>( n );
		net.resetState();

		for (int i = 0; i < n; i++ ){
			Transition t = new Transition();
			t.grid = new byte[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
			t.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ HeroEncoder.FEATURES ];
			t.actionMask = new float[ Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ ActionMapper.TARGET_COUNT ];
			t.recurrentState = new float[ net.stateSize() ];
			t.actionIndex = 2;
			t.slotIndex = 1;

			//all three heads exercised, so the reduction covers the live-head switch
			t.liveHead = i % 3;

			for (int g = 0; g < t.grid.length; g++) t.grid[ g ] = (byte) (rng.nextFloat() > 0.55f ? 1 : 0);
			for (float[] a : new float[][]{ t.inventory, t.hero, t.actionMask, t.slotMask, t.targetMask }){
				for (int k = 0; k < a.length; k++) a[ k ] = rng.nextFloat();
			}

			//the snapshot precedes the forward, matching EpisodeCollector: the state that produced this
			//step's value is the one the next step would build on
			net.saveState( t.recurrentState );
			net.forward( gridOf( t ), t.inventory, t.hero );
			t.oldLogProbability = (float) Math.log( rng.nextFloat() + 0.05f );
			t.value = net.value();

			out.add( t );
		}

		//advantages and returns, spread like GAE output rather than one sign
		Random adv = new Random( 90210L );
		for (Transition t : out){
			t.advantage = (float) (adv.nextGaussian() * 2.0 );
			t.returnValue = (float) (adv.nextGaussian() * 3.0 );
		}
		return out;
	}

	/**
	 * Runs one sample's forward and backward on {@code net}, accumulating into its gradients.
	 *
	 * <p>Through reflection because {@code PPO.oneSample} is private and the alternative is to widen the
	 * production API for the benefit of a check. Widening it would also mean the check could call it
	 * differently from the update, which is the failure mode the reflection makes impossible: the
	 * signature has to match exactly or this throws.
	 */
	static void oneSample( PPO ppo, Network net, Transition t ){
		Method m = method();
		try {
			m.invoke( ppo, net, t, new float[ net.gridLength() ],
					new float[ net.slotCount() ], new float[ net.targetCount() ],
					new float[ net.actionCount() ],
					new float[ net.slotCount() ], new float[ net.targetCount() ],
					new float[ net.actionCount() ] );
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "cannot drive PPO.oneSample", e );
		}
	}

	private static Method method(){
		try {
			Method m = PPO.class.getDeclaredMethod( "oneSample", Network.class, Transition.class,
					float[].class, float[].class, float[].class, float[].class,
					float[].class, float[].class, float[].class );
			m.setAccessible( true );
			return m;
		} catch (NoSuchMethodException e){
			throw new IllegalStateException( "PPO.oneSample is gone or renamed; this fixture is the"
					+ " only thing that knows its shape, so it fails here rather than silently testing"
					+ " something else", e );
		}
	}

	static float[] gridOf( Transition t ){
		float[] dst = new float[ t.grid.length ];
		t.unpackGrid( dst );
		return dst;
	}
}
