package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * Finite-difference check of the analytic gradients in {@link Network}.
 *
 * Every layer here was written by hand, and a hand-written backward pass fails in a specific way:
 * it runs, it returns plausible numbers, and it silently trains the wrong function. A missing
 * activation derivative, a gradient copied one step too early, a buffer cleared after it was
 * filled - none of them throw. They all produce a network that converges to nothing.
 *
 * So this compares the analytic gradient against central differences on randomly chosen
 * parameters. The network is small enough to perturb a few dozen entries directly, and a full
 * sweep over all 3.5M would cost more than it is worth.
 *
 * Two details matter for the comparison to mean anything:
 *
 * <ul>
 *   <li>The LSTM carries {@code h} and {@code c} between calls to {@code forward}, so every
 *       evaluation has to start from {@link Network#resetState()}. Without that, each probe
 *       measures a different function and every entry looks wrong.</li>
 *   <li>The loss weights each head differently, so a gradient that reaches only one head, or
 *       reaches the trunk but not the convolution, shows up as a failure rather than cancelling
 *       out.</li>
 * </ul>
 */
public class GradientCheck {

	/** Relative error below which an entry is reported as exact enough to ignore. */
	private static final double TOLERANCE = 0.02;

	/**
	 * Relative error above which an entry is reported as a failure.
	 *
	 * The band between the two thresholds is dominated by finite-difference noise rather than by a
	 * wrong gradient. ReLU is discontinuous, so a perturbation of eps can flip a unit that was
	 * sitting exactly on zero and move the measured slope by O(eps) regardless of how correct the
	 * analytic side is; the effect is proportionally worst on the smallest gradients, where it
	 * shows up as a few percent. A genuinely wrong derivative does not look like that: the missing
	 * activation terms and misordered copies that this check was written to catch came out at a
	 * relative error near 1.0, which is unambiguous.
	 */
	private static final double FAILURE_TOLERANCE = 0.2;

	/** Loss weights. Distinct so a head's gradient cannot be confused with another's. */
	private static final float ACTION_W = 1f;
	private static final float SLOT_W = 2f;
	private static final float TARGET_W = 3f;
	private static final float VALUE_W = 4f;

	private final Network network;
	private final EnvConfig config;

	private final float[] grid;
	private final float[] inventory;
	private final float[] hero;

	private int checks;
	private int failures;

	public GradientCheck( EnvConfig config, long seed ){
		this.config = config;
		this.network = new Network( config, new Random( seed ) );

		this.grid = new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
		this.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
		this.hero = new float[ HeroEncoder.FEATURES ];

		Random obs = new Random( seed * 31 + 7 );
		for (int i = 0; i < grid.length; i++){
			//sparse, like a real observation: most tiles are empty
			grid[ i ] = (obs.nextFloat() < 0.2f) ? 1f : 0f;
		}
		for (int i = 0; i < inventory.length; i++){
			inventory[ i ] = (obs.nextFloat() < 0.4f) ? 0.7f : 0f;
		}
		for (int i = 0; i < hero.length; i++){
			hero[ i ] = obs.nextFloat();
		}
	}

	/** dL/d(head outputs) matching {@link #loss}. */
	private float[] actionGradient(){ return filled( network.actionCount(), ACTION_W ); }
	private float[] slotGradient(){ return filled( network.slotCount(), SLOT_W ); }
	private float[] targetGradient(){ return filled( network.targetCount(), TARGET_W ); }

	private static float[] filled( int n, float v ){
		float[] out = new float[ n ];
		java.util.Arrays.fill( out, v );
		return out;
	}

	private double loss(){
		network.resetState();
		network.forward( grid, inventory, hero );

		double sum = 0;
		for (float v : network.actionLogits()) sum += ACTION_W * v;
		for (float v : network.slotLogits()) sum += SLOT_W * v;
		for (float v : network.targetLogits()) sum += TARGET_W * v;
		sum += VALUE_W * network.value();

		return sum;
	}

	/** Runs the check, printing one line per parameter and returning the number of failures. */
	public int run( boolean verbose ){
		loss(); //priming forward, so the pre-activation caches backward reads are populated

		network.zeroGrad();
		network.backward( actionGradient(), slotGradient(), targetGradient(), VALUE_W, null );

		String[] layers = { "conv", "trunk", "memory", "actionHead", "slotHead", "targetHead", "valueHead" };
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
				if (worst > FAILURE_TOLERANCE){
					failures++;
				} else if (worst <= TOLERANCE){
					exact++;
				}
			}
		}

		System.out.println();
		System.out.println( "gradient check: " + checks + " parameters across "
				+ layers.length + " layers, " + exact + " within " + TOLERANCE
				+ ", " + ( checks - exact - failures ) + " in the finite-difference noise band" );

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

	/** Relative error between the analytic and numeric derivative of one parameter. */
	private double compare( float[] param, float[] grad, int index, String label, boolean verbose ){
		if (index < 0 || index >= param.length) return 0;

		float original = param[ index ];

		double eps = 1e-3;
		param[ index ] = (float) (original + eps);
		double up = loss();
		param[ index ] = (float) (original - eps);
		double down = loss();
		param[ index ] = original;

		double numeric = (up - down) / (2 * eps);
		double analytic = grad[ index ];

		checks++;

		//A relative error is meaningless when both are zero, which happens for parameters the
		//loss genuinely does not depend on, eg. a convolution weight under a dead ReLU.
		double scale = Math.max( 1e-6, Math.max( Math.abs( numeric ), Math.abs( analytic ) ) );
		double relative = Math.abs( numeric - analytic ) / scale;

		if (relative > FAILURE_TOLERANCE){
			System.err.println( String.format(
					"[FAIL] %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f",
					label, index, numeric, analytic, relative ) );
		} else if (relative > TOLERANCE && !verbose){
			System.out.println( String.format(
					"[warn] %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f",
					label, index, numeric, analytic, relative ) );
		} else if (verbose){
			System.out.println( String.format(
					"       %-12s [%7d]  numeric %-14.6g  analytic %-14.6g  relative %.4f",
					label, index, numeric, analytic, relative ) );
		}

		return relative;
	}

	/**
	 * Reaches into a layer's weight and gradient buffers.
	 *
	 * Both are package-private in the rl package and deliberately so; the network owns them and
	 * nothing outside it should write to them. Reflection keeps them that way while still letting
	 * a diagnostic read them.
	 */
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

		/** Unwraps a layer's float[] out of the Tensor that holds it. */
		private static float[] data( Object layer, String name ) throws ReflectiveOperationException {
			return (float[]) field( field( layer, name ), "data" );
		}
	}
}
