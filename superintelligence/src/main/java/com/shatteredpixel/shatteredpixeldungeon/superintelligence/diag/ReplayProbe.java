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

public class ReplayProbe {

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		config.gridWidth = 12;
		config.gridHeight = 12;
		config.maxSlots = 4;

		Network network = new Network( config, new Random( 31337L ) );
		Random rng = new Random( 4242L );

		int n = 12;
		List<Transition> transitions = new ArrayList<>();
		float[] scratch = new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ];

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

			network.forward( scratchFrom( t, scratch ), t.inventory, t.hero );
			t.value = network.value();

			transitions.add( t );
		}

		double correctDrift = 0;
		double staleDrift = 0;
		double worstCorrect = 0;
		double worstStale = 0;

		network.resetState();
		for (int i = 0; i < n; i++){
			Transition t = transitions.get( i );
			network.forward( scratchFrom( t, scratch ), t.inventory, t.hero );
			double d = Math.abs( network.value() - t.value );
			correctDrift += d;
			worstCorrect = Math.max( worstCorrect, d );
		}

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
