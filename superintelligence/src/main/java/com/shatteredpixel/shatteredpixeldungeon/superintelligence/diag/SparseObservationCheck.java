package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.Arrays;
import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class SparseObservationCheck {

	private static final int CHECKS = 2;

	private static final String[] SEEDS = { "SPARSE-A", "SPARSE-B", "SPARSE-C" };

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "sparsecheck" ));
		HeadlessServices.disableSaving( true );
		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Random rng = new Random( 7 );

		Transition full = new Transition(), sparse = new Transition();
		full.gridCells = sparse.gridCells = env.grid().length;
		full.grid = new byte[ Transition.packedGridBytes( full.gridCells ) ];
		sparse.grid = new byte[ full.grid.length ];
		float[] uniform = new float[ Action.size() ];

		int steps = 0, unlisted = 0, packDiffers = 0, nonZero = 0;
		for (String seed : SEEDS){
			env.reset( seed, HeroClass.values()[ steps % HeroClass.values().length ] );
			for (int t = 0; t < 400 && env.running(); t++, steps++){
				float[] grid = env.grid();
				boolean[] listed = new boolean[ grid.length ];
				for (int i = 0; i < env.gridTouchedCount(); i++) listed[ env.gridTouched()[ i ] ] = true;
				for (int i = 0; i < grid.length; i++){
					if (grid[ i ] == 0f) continue;
					nonZero++;
					if (!listed[ i ]) unlisted++;
				}
				full.packGrid( grid );
				sparse.packGrid( grid, env.gridTouched(), env.gridTouchedCount() );
				if (!Arrays.equals( full.grid, sparse.grid )) packDiffers++;
				int action = Policy.sample( uniform, env.actionMask(), rng );
				int secondary = env.mode() == EnvMode.TARGETING ? 0 : rng.nextInt( Math.max( 1, config.maxSlots ));
				env.step( Action.fromIndex( action ), secondary );
			}
		}

		if (unlisted > 0) fail( unlisted + " of " + nonZero + " nonzero observation cells over " + steps
				+ " steps were not in the encoder's touched list, so clearing only the listed cells leaves stale values" );
		else System.out.println( "  all " + nonZero + " nonzero cells over " + steps + " steps are listed, so a listed clear equals a full one" );
		if (packDiffers > 0) fail( packDiffers + " of " + steps + " steps packed differently from the touched list than from the full grid" );
		else System.out.println( "  the sparse pack equals the full pack on all " + steps + " steps" );

		System.exit( GateReport.report( "sparse observation", CHECKS ));
	}
}
