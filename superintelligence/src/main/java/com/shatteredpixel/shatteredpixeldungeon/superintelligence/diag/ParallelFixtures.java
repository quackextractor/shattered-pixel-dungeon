package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class ParallelFixtures {

	private ParallelFixtures() {}

	static List< Transition > batch( Network net, EnvConfig config, int n, long seed ){
		Random rng = new Random( seed );
		List< Transition > out = new ArrayList<>( n );
		net.resetState();

		for (int i = 0; i < n; i++ ){
			Transition t = new Transition();
			t.gridCells = net.gridLength();
		t.grid = new byte[ Transition.packedGridBytes( t.gridCells ) ];
			t.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ HeroEncoder.FEATURES ];
			t.actionMask = new float[ Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ ActionMapper.TARGET_COUNT ];
			t.recurrentState = new float[ net.stateSize() ];
			t.actionIndex = 2;
			t.slotIndex = 1;

			t.liveHead = i % 3;

		float[] grid = new float[ t.gridCells ];
		for (int g = 0; g < grid.length; g++) grid[ g ] = rng.nextFloat() > 0.55f ? 1f : 0f;
		t.packGrid( grid );
			for (float[] a : new float[][]{ t.inventory, t.hero, t.actionMask, t.slotMask, t.targetMask }){
				for (int k = 0; k < a.length; k++) a[ k ] = rng.nextFloat();
			}

			net.saveState( t.recurrentState );
			net.forward( gridOf( t ), t.inventory, t.hero );
			t.oldLogProbability = (float) Math.log( rng.nextFloat() + 0.05f );
			t.value = net.value();

			out.add( t );
		}

		Random adv = new Random( 90210L );
		for (Transition t : out){
			t.advantage = (float) (adv.nextGaussian() * 2.0 );
			t.returnValue = (float) (adv.nextGaussian() * 3.0 );
		}
		return out;
	}

	static float[] gridOf( Transition t ){
		float[] dst = new float[ t.gridCells ];
		t.unpackGrid( dst );
		return dst;
	}
}
