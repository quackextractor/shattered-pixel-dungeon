package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.StepCache;

import java.lang.reflect.Field;
import java.util.Random;


public class GradientCheck {


	private static final double TOLERANCE = 0.02;

	private static final double FAILURE_TOLERANCE = 0.2;

	private static final double DIFFERENCE_FLOOR = 8.0;


	private static final float ACTION_W = 1f;
	private static final float SLOT_W = 2f;
	private static final float TARGET_W = 3f;
	private static final float VALUE_W = 4f;

	private final Network network;
	private static final int STEPS = 3;

	private final float[][] grids = new float[ STEPS ][];
	private final float[][] inventories = new float[ STEPS ][];
	private final float[][] heroes = new float[ STEPS ][];
	private final float[] initialState;

	private int checks;
	private int failures;

	public GradientCheck( EnvConfig config, long seed ){
		this.network = new Network( config, new Random( seed ) );

		Random obs = new Random( seed * 31 + 7 );
		for (int t = 0; t < STEPS; t++){
			grids[ t ] = new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
			inventories[ t ] = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
			heroes[ t ] = new float[ HeroEncoder.FEATURES ];
			for (int i = 0; i < grids[ t ].length; i++) grids[ t ][ i ] = (obs.nextFloat() < 0.2f) ? 1f : 0f;
			for (int i = 0; i < inventories[ t ].length; i++) inventories[ t ][ i ] = (obs.nextFloat() < 0.4f) ? 0.7f : 0f;
			for (int i = 0; i < heroes[ t ].length; i++) heroes[ t ][ i ] = obs.nextFloat();
		}

		this.initialState = new float[ network.stateSize() ];
		for (int i = 0; i < initialState.length; i++) initialState[ i ] = obs.nextFloat() - 0.5f;
	}

	private float[] actionGradient(){ return filled( network.actionCount(), ACTION_W ); }
	private float[] slotGradient(){ return filled( network.slotCount(), SLOT_W ); }
	private float[] targetGradient(){ return filled( network.targetCount(), TARGET_W ); }

	private static float[] filled( int n, float v ){
		float[] out = new float[ n ];
		java.util.Arrays.fill( out, v );
		return out;
	}

	private double loss(){
		network.loadState( initialState );

		double sum = 0;
		for (int t = 0; t < STEPS; t++){
			network.forward( grids[ t ], inventories[ t ], heroes[ t ] );
			for (float v : network.actionLogits()) sum += ACTION_W * v;
			for (float v : network.slotLogits()) sum += SLOT_W * v;
			for (float v : network.targetLogits()) sum += TARGET_W * v;
			sum += VALUE_W * network.value();
		}

		return sum;
	}


	public int run( boolean verbose ){
		StepCache[] caches = new StepCache[ STEPS ];
		network.loadState( initialState );
		for (int t = 0; t < STEPS; t++){
			caches[ t ] = network.takeCache();
			network.forwardCached( grids[ t ], inventories[ t ], heroes[ t ], caches[ t ] );
		}

		network.zeroGrad();
		network.beginBackward();
		for (int t = STEPS - 1; t >= 0; t--){
			network.backwardStep( actionGradient(), slotGradient(), targetGradient(), VALUE_W, caches[ t ] );
		}

		String[] layers = { "conv", "reduce", "trunk", "memory", "actionHead", "slotHead", "targetHead", "valueHead" };
		Random picker = new Random( 0xC0FFEE );

		int exact = 0;

		for (String name : layers){
			LayerAccess access = LayerAccess.of( network, name );

			int samples = Math.min( 8, access.parameterCount() );
			for (int i = 0; i < samples; i++){
				double worst = 0;
				worst = Math.max( worst, compare( access.weights, access.weightGrad,
						picker.nextInt( access.weights.length ), name + " W", verbose ) );
				worst = Math.max( worst, compare( access.bias, access.biasGrad,
						picker.nextInt( access.bias.length ), name + " b", verbose ) );
				if (worst <= TOLERANCE) exact++;
			}
		}

		LayerAccess memory = LayerAccess.of( network, "memory" );
		int recurrentFrom = network.memory.inputSize * 4 * network.memory.size;
		for (int i = 0; i < 8; i++){
			int index = recurrentFrom + picker.nextInt( memory.weights.length - recurrentFrom );
			if (compare( memory.weights, memory.weightGrad, index, "memory Wh", verbose ) <= TOLERANCE) exact++;
		}

		System.out.println();
		System.out.println( "gradient check: " + checks + " parameters over a " + STEPS + "-step chunk across "
				+ layers.length + " layers, " + exact + " within " + TOLERANCE
				+ ", " + ( checks - exact - failures ) + " in the noise band" );

		if (failures > 0){
			System.err.println( "[ERROR] " + failures + " parameters disagree with finite"
					+ " differences by more than " + FAILURE_TOLERANCE + " relative" );
			System.err.println( "        the backward pass is wrong; training on it would" );
			System.err.println( "        optimise a function other than the policy loss" );
			return failures;
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN )
				+ "     analytic gradients match central differences" );
		return 0;
	}


	private double central( float[] param, int index, float original, double eps ){
		param[ index ] = (float) (original + eps );
		double up = loss();
		param[ index ] = (float) (original - eps );
		double down = loss();
		param[ index ] = original;
		return (up - down) / (2 * eps );
	}

private static double differenceFloor( double center, double eps ){
		return DIFFERENCE_FLOOR * Math.abs( center ) * 1.1920929e-7 / eps;
	}


	private double compare( float[] param, float[] grad, int index, String label, boolean verbose ){
		if (index < 0 || index >= param.length) return 0;

		float original = param[ index ];

		double eps = 1e-3;
		double center = loss();
		double numeric = central( param, index, original, eps );
		double analytic = grad[ index ];

		checks++;

		double scale = Math.max( Math.abs( numeric ), Math.abs( analytic ) );

		double noise = Math.max( differenceFloor( center, eps ),
				Math.abs( central( param, index, original, eps * 2 )
						- central( param, index, original, eps * 0.5 ) ) );

		double disagreement = Math.abs( numeric - analytic );
		double relative = disagreement / scale;

		boolean ok = disagreement <= Math.max( FAILURE_TOLERANCE * scale, noise );

		if (!ok) failures++;
		if (!ok){
			System.err.println( String.format(
					"[FAIL] %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f  noise %.4g",
					label, index, numeric, analytic, relative, noise ) );
		} else if (relative > TOLERANCE && !verbose){
			System.out.println( String.format(
					"[warn] %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f  noise %.4g",
					label, index, numeric, analytic, relative, noise ) );
		} else if (verbose){
			System.out.println( String.format(
					"       %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f",
					label, index, numeric, analytic, relative ) );
		}

		return relative;
	}


	private static class LayerAccess {
		final float[] weights;
		final float[] weightGrad;
		final float[] bias;
		final float[] biasGrad;

		private LayerAccess( float[] weights, float[] weightGrad, float[] bias, float[] biasGrad ){
			this.weights = weights;
			this.weightGrad = weightGrad;
			this.bias = bias;
			this.biasGrad = biasGrad;
		}

		int parameterCount(){ return weights.length + bias.length; }

		static LayerAccess of( Network net, String field ) {
			try {
				Object layer = field( net, field );
				return new LayerAccess(
						data( layer, "W" ),
						data( layer, "gW" ),
						data( layer, "b" ),
						data( layer, "gb" ) );
			} catch (ReflectiveOperationException e){
				throw new IllegalStateException( "could not inspect layer " + field, e );
			}
		}

		private static Object field( Object target, String name ) throws ReflectiveOperationException {
			Field f = target.getClass().getDeclaredField( name );
			f.setAccessible( true );
			return f.get( target );
		}


		private static float[] data( Object layer, String name ) throws ReflectiveOperationException {
			return (float[]) field( field( layer, name ), "data" );
		}
	}
}
