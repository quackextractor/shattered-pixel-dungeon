package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

/**
 * A single LSTM cell, one recurrent step.
 *
 * research.md: "Pixel Dungeon is a Partially Observable Markov Decision Process. Because of the
 * Fog of War, the AI cannot see the whole map. A recurrent layer like an LSTM allows the AI to
 * maintain a hidden state or 'memory'. This is strictly necessary so the AI can remember that it
 * left a health potion three rooms behind, or remember the location of the stairs after exploring
 * a dead end."
 *
 * Gate order matches the usual formulation: forget, input, candidate, output. Hidden state is
 * carried across steps of an episode and reset by {@link #reset} between episodes.
 *
 * Consecutive timesteps are batched: an episode of T steps becomes one row of T per layer, and the
 * loop over T is what happens. That keeps the GEMMs large, which is the only way a hand-written
 * LSTM in Java is fast enough to train on.
 */
public class LSTM {

	public final int size;

	//fused input weights: (inputSize + size) x 4*size, gates concatenated
	final Tensor W;
	final Tensor b;

	final Tensor mW, vW, mb, vb;
	final Tensor gW, gb;

//per-step scratch, reused
	private final Tensor gates;
	private final Tensor scratch;

	//per-step derivative scratch, reused
	private final Tensor dCellGrad;
	private final Tensor dGateGrad;

/** Carried between timesteps. */
	private final Tensor h;
	private final Tensor c;

	/** Cached by forward, read by backward: everything backward needs from before the step. */
	private final Tensor hPrev;
	private final Tensor cPrev;
	private final Tensor preAct;

	public final int inputSize;

	public LSTM( int inputSize, int size, Random rng ){
		this.size = size;
		this.inputSize = inputSize;

		int rows = inputSize + size;
		int cols = size * 4;

		this.W = new Tensor( rows, cols );
		this.b = new Tensor( 1, cols );

		this.mW = new Tensor( rows, cols );
		this.vW = new Tensor( rows, cols );
		this.mb = new Tensor( 1, cols );
		this.vb = new Tensor( 1, cols );

		this.gW = new Tensor( rows, cols );
		this.gb = new Tensor( 1, cols );

this.gates = new Tensor( 1, cols );
		this.scratch = new Tensor( 1, rows );
		this.inputGrad = new float[ inputSize ];

		this.dCellGrad = new Tensor( 1, size );
		this.dGateGrad = new Tensor( 1, size * 4 );

this.h = new Tensor( 1, size );
		this.c = new Tensor( 1, size );

		this.hPrev = new Tensor( 1, size );
		this.cPrev = new Tensor( 1, size );
		this.preAct = new Tensor( 1, cols );

		//forget gate bias of 1 is the standard initialisation: start by remembering
		for (int i = 0; i < size; i++) b.data[ i ] = 1f;

		float scale = (float) Math.sqrt( 2.0 / cols );
		W.randomNormal( rng, scale );
	}

	public void reset(){
		h.fill( 0f );
		c.fill( 0f );
	}

	/**
	 * The carried hidden state, live.
	 *
	 * <p>Exposed as a live tensor so a caller can snapshot it without a copy. {@link #snapshot} is the
	 * safe way to take one; this is for the update's in-place restore.
	 */
	public Tensor state(){
		return h;
	}

	/** The cell state, live. See {@link #state}. */
	public Tensor cellState(){
		return c;
	}

	/**
	 * Copies both carried states out into {@code into}, {@code [h | c]}.
	 *
	 * <p>Both halves, because the cell state is not derivable from the hidden one — the forget gate's
	 * accumulated memory lives only in {@code c}, and restoring {@code h} with a zeroed {@code c} gives
	 * a state that has never existed and produces a value that looks merely plausible.
	 */
	public void snapshot( float[] into ){
		if (into == null || into.length < 2 * size) return;
		System.arraycopy( h.data, 0, into, 0, size );
		System.arraycopy( c.data, 0, into, size, size );
	}

	/** Restores both carried states from a {@link #snapshot}. */
	public void restore( float[] from ){
		if (from == null || from.length < 2 * size) return;
		System.arraycopy( from, 0, h.data, 0, size );
		System.arraycopy( from, size, c.data, 0, size );
	}

	public void stateFrom( Tensor other ){
		h.copyFrom( other );
	}

/** out = LSTM(x, h_prev, c_prev). Advances the carried state. */
	public void forward( Tensor x, Tensor out ){
		// concatenate x and h into the scratch row
		System.arraycopy( x.data, 0, scratch.data, 0, x.cols );
		System.arraycopy( h.data, 0, scratch.data, x.cols, size );

		scratch.matmul( W, b, gates );

		//backward needs the gate pre-activations and both prior states, because h and c are
		//overwritten below
		System.arraycopy( gates.data, 0, preAct.data, 0, gates.cols );
		System.arraycopy( h.data, 0, hPrev.data, 0, size );
		System.arraycopy( c.data, 0, cPrev.data, 0, size );

		int s = size;
		for (int i = 0; i < s; i++){
			float f = sigmoid( gates.data[ i ] );
			float in = sigmoid( gates.data[ s + i ] );
			float g = (float) Math.tanh( gates.data[ 2 * s + i ] );
			float o = sigmoid( gates.data[ 3 * s + i ] );

			c.data[ i ] = f * c.data[ i ] + in * g;
			h.data[ i ] = o * (float) Math.tanh( c.data[ i ] );
			out.data[ i ] = h.data[ i ];
		}
	}

	/**
	 * Accumulates gradients through one step.
	 *
	 * @param x      the input that was fed to forward
	 * @param dOut   dL/dh_t
	 * @param dHPrev dL/dh_{t-1}, accumulated into
	 * @param dCPrev dL/dc_{t-1}, accumulated into
	 */
public void backward( Tensor x, Tensor dOut, Tensor dHPrev, Tensor dCPrev ){
		int s = size;

		java.util.Arrays.fill( inputGrad, 0f );

		float[] gc = dCellGrad.data;
		float[] gg = dGateGrad.data;

		for (int i = 0; i < s; i++){
			float f = sigmoid( preAct.data[ i ] );
			float in = sigmoid( preAct.data[ s + i ] );
			float g = (float) Math.tanh( preAct.data[ 2 * s + i ] );
			float o = sigmoid( preAct.data[ 3 * s + i ] );

			//c_t is still live at this point because backward runs immediately after forward
			float ct = c.data[ i ];
			float tanhCt = (float) Math.tanh( ct );

			//total gradient arriving at c_t
			float dc = dCPrev.data[ i ] + dOut.data[ i ] * o * (1 - tanhCt * tanhCt);

			//dL/dgates, with the previous cell state on the forget gate
			gg[ i ]         = dc * cPrev.data[ i ] * f * (1 - f);
			gg[ s + i ]     = dc * g * in * (1 - in);
			gg[ 2 * s + i ] = dc * in * (1 - g * g);
			gg[ 3 * s + i ] = dOut.data[ i ] * tanhCt * o * (1 - o);

			//dL/dc_{t-1}. h_{t-1} has no other path into the output, so its gradient arrives
			//entirely through the gates below.
			gc[ i ] = dc * f;
		}

		//dW += [x; h_prev]^T * dgates, db += dgates
		for (int r = 0; r < scratch.cols; r++){
			float xv = scratch.data[ r ];
			if (xv == 0f) continue;
			int row = r * W.cols;
			for (int n = 0; n < W.cols; n++) gW.data[ row + n ] += xv * gg[ n ];
		}
		for (int n = 0; n < W.cols; n++) gb.data[ n ] += gg[ n ];

		//[x; h_prev] gradient: the x rows feed the layer below, the h rows feed dHPrev
		for (int r = 0; r < scratch.cols; r++){
			int row = r * W.cols;
			float sum = 0;
			for (int n = 0; n < W.cols; n++) sum += W.data[ row + n ] * gg[ n ];

			if (r < x.cols){
				inputGrad[ r ] += sum;
			} else {
				dHPrev.data[ r - x.cols ] += sum;
			}
		}

		//dc_{t-1} in place, so the caller can pass its incoming gradient straight through
		System.arraycopy( gc, 0, dCPrev.data, 0, s );
	}

/**
 * Scratch the input gradient is written into.
 *
 * The cell owns this buffer rather than taking one as a parameter, because it has to match the
 * input width and threading it through the caller's forward order would leak LSTM internals out.
 * backward() clears and refills it every call, so a caller that reads it straight after backward
 * always sees the gradient for that one step.
 */
	private final float[] inputGrad;

	public float[] inputGrad(){
		return inputGrad;
	}

	/** Number of leading entries of {@link #inputGrad()} that backward() wrote. */
	public int inputGradLen(){
		return inputGrad.length;
	}

	public void zeroGrad(){
		gW.fill( 0f );
		gb.fill( 0f );
	}

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

	private static float sigmoid( float x ){
		//branch on sign to avoid overflow in exp for large negative inputs
		if (x >= 0) return (float) (1.0 / (1.0 + Math.exp( -x )));
		float e = (float) Math.exp( x );
		return e / (1f + e);
	}

	private static float tanhSq( float x ){
		float t = (float) Math.tanh( x );
		return t * t;
	}
}