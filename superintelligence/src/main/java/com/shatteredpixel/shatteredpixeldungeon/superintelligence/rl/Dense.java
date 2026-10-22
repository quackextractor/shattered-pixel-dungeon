package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

public class Dense {

	public final Tensor W;
	public final Tensor b;

	private final boolean tanh;

	final Tensor mW, vW, mb, vb;

	final Tensor gW, gb;

	private final Tensor preAct;

	public final int in;
	public final int out;

	public Dense( int in, int out, Random rng, float weightScale ){
		this( in, out, rng, weightScale, true );
	}

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

		W.randomNormal( rng, weightScale / (float) Math.sqrt( in ) );
		b.fill( 0f );
	}

	public void forward( Tensor x, Tensor out ){
		x.matmul( W, b, out );
		System.arraycopy( out.data, 0, preAct.data, 0, out.cols );
		if (!tanh) return;
		for (int i = 0; i < out.data.length; i++){
			out.data[ i ] = (float) Math.tanh( out.data[ i ] );
		}
	}

	public void backward( Tensor x, Tensor dout, Tensor dx ){

		if (tanh){
			for (int n = 0; n < out; n++){
				float t = (float) Math.tanh( preAct.data[ n ] );
				dout.data[ n ] *= (1f - t * t);
			}
		}

		for (int m = 0; m < in; m++){
			float xv = x.data[ m ];
			if (xv == 0f) continue;
			int row = m * out;
			for (int n = 0; n < out; n++){
				gW.data[ row + n ] += xv * dout.data[ n ];
			}
		}

		for (int n = 0; n < out; n++) gb.data[ n ] += dout.data[ n ];

		for (int m = 0; m < in; m++){
			float sum = 0;
			int row = m * out;
			for (int n = 0; n < out; n++){
				sum += dout.data[ n ] * W.data[ row + n ];
			}
			dx.data[ m ] = sum;
		}
	}

	public void forwardSteps( float[][] x, float[][] y, float[][] pre, int steps ){
		float[] w = W.data;
		for (int t = 0; t < steps; t++) java.util.Arrays.fill( y[ t ], 0, out, 0f );
		for (int m = 0; m < in; m++){
			int row = m * out;
			for (int t = 0; t < steps; t++){
				float xv = x[ t ][ m ];
				if (xv == 0f) continue;
				float[] o = y[ t ];
				for (int n = 0; n < out; n++) o[ n ] += xv * w[ row + n ];
			}
		}
		for (int t = 0; t < steps; t++){
			float[] o = y[ t ];
			for (int n = 0; n < out; n++){
				o[ n ] += b.data[ n ];
				pre[ t ][ n ] = o[ n ];
				if (tanh) o[ n ] = (float) Math.tanh( o[ n ] );
			}
		}
	}

	public void backwardSteps( float[][] x, float[][] pre, float[][] dy, float[][] dx, int steps ){
		float[] w = W.data, gw = gW.data;
		if (tanh){
			for (int t = 0; t < steps; t++){
				for (int n = 0; n < out; n++){
					float th = (float) Math.tanh( pre[ t ][ n ] );
					dy[ t ][ n ] *= (1f - th * th);
				}
			}
		}
		for (int m = 0; m < in; m++){
			int row = m * out;
			for (int t = steps - 1; t >= 0; t--){
				float xv = x[ t ][ m ];
				if (xv == 0f) continue;
				float[] d = dy[ t ];
				for (int n = 0; n < out; n++) gw[ row + n ] += xv * d[ n ];
			}
			for (int t = 0; t < steps; t++){
				float[] d = dy[ t ];
				float sum = 0;
				for (int n = 0; n < out; n++) sum += d[ n ] * w[ row + n ];
				dx[ t ][ m ] = sum;
			}
		}
		for (int t = steps - 1; t >= 0; t--){
			for (int n = 0; n < out; n++) gb.data[ n ] += dy[ t ][ n ];
		}
	}

	public void savePreAct( float[] into ){
		System.arraycopy( preAct.data, 0, into, 0, preAct.cols );
	}

	public void loadPreAct( float[] from ){
		System.arraycopy( from, 0, preAct.data, 0, preAct.cols );
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
}