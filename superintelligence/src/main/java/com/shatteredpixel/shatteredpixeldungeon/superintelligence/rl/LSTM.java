package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;


public class LSTM {

	public final int size;

	final Tensor W;
	final Tensor b;

	final Tensor mW, vW, mb, vb;
	final Tensor gW, gb;

	private final Tensor gates;
	private final Tensor scratch;

	private final Tensor dCellGrad;
	private final Tensor dGateGrad;


	private final Tensor h;
	private final Tensor c;


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

		for (int i = 0; i < size; i++) b.data[ i ] = 1f;

		float scale = (float) Math.sqrt( 2.0 / cols );
		W.randomNormal( rng, scale );
	}

	public void reset(){
		h.fill( 0f );
		c.fill( 0f );
	}


	public Tensor state(){
		return h;
	}


	public Tensor cellState(){
		return c;
	}


	public void snapshot( float[] into ){
		if (into == null || into.length < 2 * size) return;
		System.arraycopy( h.data, 0, into, 0, size );
		System.arraycopy( c.data, 0, into, size, size );
	}


	public void restore( float[] from ){
		if (from == null || from.length < 2 * size) return;
		System.arraycopy( from, 0, h.data, 0, size );
		System.arraycopy( from, size, c.data, 0, size );
	}

	public void stateFrom( Tensor other ){
		h.copyFrom( other );
	}


	public int cacheLength(){
		return preAct.cols + hPrev.cols + cPrev.cols + c.cols + scratch.cols;
	}


	public void saveCache( float[] into ){
		int at = 0;
		at = copy( preAct.data, into, at );
		at = copy( hPrev.data, into, at );
		at = copy( cPrev.data, into, at );
		at = copy( c.data, into, at );
		copy( scratch.data, into, at );
	}


	public void loadCache( float[] from ){
		int at = 0;
		at = load( preAct.data, from, at );
		at = load( hPrev.data, from, at );
		at = load( cPrev.data, from, at );
		at = load( c.data, from, at );
		load( scratch.data, from, at );
	}

	private static int copy( float[] src, float[] dst, int at ){
		System.arraycopy( src, 0, dst, at, src.length );
		return at + src.length;
	}

	private static int load( float[] dst, float[] src, int at ){
		System.arraycopy( src, at, dst, 0, dst.length );
		return at + dst.length;
	}


	public void forward( Tensor x, Tensor out ){

		System.arraycopy( x.data, 0, scratch.data, 0, x.cols );
		System.arraycopy( h.data, 0, scratch.data, x.cols, size );

		scratch.matmul( W, b, gates );

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

			float ct = c.data[ i ];
			float tanhCt = (float) Math.tanh( ct );

			float dc = dCPrev.data[ i ] + dOut.data[ i ] * o * (1 - tanhCt * tanhCt);

			gg[ i ]         = dc * cPrev.data[ i ] * f * (1 - f);
			gg[ s + i ]     = dc * g * in * (1 - in);
			gg[ 2 * s + i ] = dc * in * (1 - g * g);
			gg[ 3 * s + i ] = dOut.data[ i ] * tanhCt * o * (1 - o);

			gc[ i ] = dc * f;
		}

		for (int r = 0; r < scratch.cols; r++){
			float xv = scratch.data[ r ];
			if (xv == 0f) continue;
			int row = r * W.cols;
			for (int n = 0; n < W.cols; n++) gW.data[ row + n ] += xv * gg[ n ];
		}
		for (int n = 0; n < W.cols; n++) gb.data[ n ] += gg[ n ];

		for (int r = 0; r < scratch.cols; r++){
			int row = r * W.cols;
			float sum = 0;
			for (int n = 0; n < W.cols; n++) sum += W.data[ row + n ] * gg[ n ];

			if (r < x.cols){
				inputGrad[ r ] += sum;
			} else {
				dHPrev.data[ r - x.cols ] = sum;
			}
		}

		System.arraycopy( gc, 0, dCPrev.data, 0, s );
	}


	private final float[] inputGrad;

	public float[] inputGrad(){
		return inputGrad;
	}


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

		if (x >= 0) return (float) (1.0 / (1.0 + Math.exp( -x )));
		float e = (float) Math.exp( x );
		return e / (1f + e);
	}

	private static float tanhSq( float x ){
		float t = (float) Math.tanh( x );
		return t * t;
	}
}