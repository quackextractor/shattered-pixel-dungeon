package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

/**
 * A fully connected layer with tanh activation and its Adam state.
 *
 * Adam rather than plain SGD because the observation feeds this layer a mix of very differently
 * scaled quantities (normalised vitals, raw item upgrade counts, a type embedding index). Plain
 * SGD with one learning rate either crawls on the small features or diverges on the large ones.
 *
 * Gradients accumulate into a caller-owned buffer so the same layer instance can be reused across
 * a whole minibatch without reallocating.
 */
public class Dense {

	public final Tensor W;
	public final Tensor b;

	/**
	 * Whether to apply tanh. Off for the value head, which is a regression and not a classifier.
	 *
	 * <p>A tanh output is confined to {@code [-1, +1]}, and {@code EnvConfig.deathPenalty} is 100 with
	 * {@code victoryReward} 1000. So the critic was being asked to fit targets up to 250x outside the only
	 * range it could express: those samples contribute a squared error no amount of training removes,
	 * and {@code valueLoss} floors out instead of falling. Measured over 12 generations it sat at
	 * 2.97 to 8.11 and did not trend down.
	 *
	 * <p>The loss changes with it. For a linear output the squared-error gradient is
	 * {@code dL/dpreAct = (pred - target) * feature}; the {@code 1 - tanh'} factor is the tanh case alone.
	 */
	private final boolean tanh;

	/** Adam moments. */
	final Tensor mW, vW, mb, vb;

	/** Gradient accumulators for the current minibatch. */
	final Tensor gW, gb;

	/** Pre-activation from the last forward, needed for the tanh derivative in backward. */
	private final Tensor preAct;

	public final int in;
	public final int out;

	public Dense( int in, int out, Random rng, float weightScale ){
		this( in, out, rng, weightScale, true );
	}

	/**
	 * @param tanh whether to squash the output into [-1, 1]. False for a linear head.
	 */
	public Dense( int in, int out, Random rng, float weightScale, boolean tanh ){
		this.in = in;
		this.out = out;
		this.tanh = tanh;

		this.W = new Tensor( in, out );
		this.b = new Tensor( 1, out );

		this.mW = new Tensor( in, out );
		this.vW = new Tensor( in, out );
		this.mb = new Tensor( 1, out );
		this.vb = new Tensor( 1, out );

		this.gW = new Tensor( in, out );
		this.gb = new Tensor( 1, out );

		this.preAct = new Tensor( 1, out );

		//Xavier: keeps the activation variance stable across depth
		W.randomNormal( rng, weightScale / (float) Math.sqrt( in ) );
		b.fill( 0f );
	}

	/** out = tanh(x * W + b), or {@code x * W + b} when this layer is linear. */
	public void forward( Tensor x, Tensor out ){
		x.matmul( W, b, out );
		System.arraycopy( out.data, 0, preAct.data, 0, out.cols );
		if (!tanh) return;
		for (int i = 0; i < out.data.length; i++){
			out.data[ i ] = (float) Math.tanh( out.data[ i ] );
		}
	}

	/** Accumulates dL/dx given dL/dout, where out was produced by forward(x, out). */
	public void backward( Tensor x, Tensor dout, Tensor dx ){
		//dL/d(pre-activation): chain the tanh derivative in before touching anything downstream. A
		//linear layer's derivative is 1, so the chain rule stops here rather than multiplying by a
		//saturating factor that would shrink the critic's gradient as its error grows - which is the
		//opposite of what a regressor needs.
		if (tanh){
			for (int n = 0; n < out; n++){
				float t = (float) Math.tanh( preAct.data[ n ] );
				dout.data[ n ] *= (1f - t * t);
			}
		}

		// dW += x^T * dout
		for (int m = 0; m < in; m++){
			float xv = x.data[ m ];
			if (xv == 0f) continue;
			int row = m * out;
			for (int n = 0; n < out; n++){
				gW.data[ row + n ] += xv * dout.data[ n ];
			}
		}

		for (int n = 0; n < out; n++) gb.data[ n ] += dout.data[ n ];

		// dx = dout * W^T
		for (int m = 0; m < in; m++){
			float sum = 0;
			int row = m * out;
			for (int n = 0; n < out; n++){
				sum += dout.data[ n ] * W.data[ row + n ];
			}
			dx.data[ m ] = sum;
		}
	}

	public void zeroGrad(){
		gW.fill( 0f );
		gb.fill( 0f );
	}

	/**
	 * Applies one Adam step.
	 *
	 * @param step the global step count, used for the bias correction
	 */
	public void adamStep( float lr, float beta1, float beta2, float eps, float gradScale, int step ){
		adam( W, gW, mW, vW, lr, beta1, beta2, eps, gradScale, step );
		adam( b, gb, mb, vb, lr, beta1, beta2, eps, gradScale, step );
	}

	private static void adam( Tensor param, Tensor grad, Tensor m, Tensor v,
			float lr, float beta1, float beta2, float eps, float gradScale, int step ){
		float bias1 = 1f - (float) Math.pow( beta1, step );
		float bias2 = 1f - (float) Math.pow( beta2, step );

		float[] p = param.data, g = grad.data, mm = m.data, vv = v.data;
		for (int i = 0; i < p.length; i++){
			float gi = g[ i ] * gradScale;
			mm[ i ] = beta1 * mm[ i ] + (1 - beta1) * gi;
			vv[ i ] = beta2 * vv[ i ] + (1 - beta2) * gi * gi;
			float mHat = mm[ i ] / bias1;
			float vHat = vv[ i ] / bias2;
			p[ i ] -= lr * mHat / ((float) Math.sqrt( vHat ) + eps);
		}
	}
}