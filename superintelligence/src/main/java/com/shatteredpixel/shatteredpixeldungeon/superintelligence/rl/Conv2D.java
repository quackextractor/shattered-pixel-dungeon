package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

public class Conv2D {

	public final Tensor W;
	public final Tensor b;

	final Tensor mW, vW, mb, vb;
	final Tensor gW, gb;

	public final int inChannels;
	public final int inWidth;
	public final int inHeight;
	public final int kernel;
	public final int stride;
	public final int outWidth;
	public final int outHeight;
	public final int outChannels;

	private final Tensor col;

	private final float[] preAct;

	private final float[] zeros;

	private boolean sparse;

	public Conv2D( int inChannels, int inWidth, int inHeight, int outChannels, int kernel, int stride, Random rng ){
		this.inChannels = inChannels;
		this.inWidth = inWidth;
		this.inHeight = inHeight;
		this.kernel = kernel;
		this.stride = stride;
		this.outChannels = outChannels;

		this.outWidth = (inWidth - kernel) / stride + 1;
		this.outHeight = (inHeight - kernel) / stride + 1;

		int patch = kernel * kernel * inChannels;
		this.W = new Tensor( patch, outChannels );
		this.b = new Tensor( 1, outChannels );

		this.mW = new Tensor( patch, outChannels );
		this.vW = new Tensor( patch, outChannels );
		this.mb = new Tensor( 1, outChannels );
		this.vb = new Tensor( 1, outChannels );

		this.gW = new Tensor( patch, outChannels );
		this.gb = new Tensor( 1, outChannels );

		this.col = new Tensor( patch, outWidth * outHeight );
		this.preAct = new float[ outWidth * outHeight * outChannels ];
		this.zeros = new float[ inWidth * inHeight ];

		W.randomNormal( rng, (float) Math.sqrt( 2.0 / patch ) );
		b.fill( 0f );
	}

	public int outputSize(){
		return outWidth * outHeight * outChannels;
	}

	public int preActivationSize(){
		return preAct.length;
	}

	public void savePreAct( float[] into ){
		System.arraycopy( preAct, 0, into, 0, Math.min( preAct.length, into.length ) );
	}

	public void loadPreAct( float[] from ){
		System.arraycopy( from, 0, preAct, 0, Math.min( preAct.length, from.length ) );
	}

	public void forward( float[] input, Tensor out ){
		int positions = outWidth * outHeight;
		float[] o = preAct, w = W.data;
		for (int p = 0; p < positions; p++) System.arraycopy( b.data, 0, o, p * outChannels, outChannels );

		int planeSize = inWidth * inHeight, nonZero = 0;
		for (int ch = 0; ch < inChannels; ch++){
			int plane = ch * planeSize, end = plane + planeSize;
			if (sparse){
				for (int at = plane;;){
					int skip = java.util.Arrays.mismatch( input, at, end, zeros, 0, end - at );
					if (skip < 0) break;
					at += skip + 1;
					float x = input[ at - 1 ];
					if (x == 0f) continue;
					scatter( x, ch, at - 1 - plane, o, w );
					nonZero++;
				}
			} else {
				for (int at = plane; at < end; at++){
					float x = input[ at ];
					if (x == 0f) continue;
					scatter( x, ch, at - plane, o, w );
					nonZero++;
				}
			}
		}
		sparse = nonZero * 10 < inChannels * planeSize;

		for (int i = 0; i < o.length; i++) out.data[ i ] = Math.max( 0f, o[ i ] );
	}

	private void scatter( float x, int ch, int cell, float[] o, float[] w ){
		int iy = cell / inWidth, ix = cell - iy * inWidth;
		int oyFirst = Math.max( 0, ( iy - kernel + stride ) / stride ), oyLast = Math.min( outHeight - 1, iy / stride );
		int oxFirst = Math.max( 0, ( ix - kernel + stride ) / stride ), oxLast = Math.min( outWidth - 1, ix / stride );
		for (int oy = oyFirst; oy <= oyLast; oy++){
			int row = ( ch * kernel + iy - oy * stride ) * kernel;
			for (int ox = oxFirst; ox <= oxLast; ox++){
				int wOff = ( row + ix - ox * stride ) * outChannels;
				int oOff = ( oy * outWidth + ox ) * outChannels;
				for (int q = 0; q < outChannels; q++) o[ oOff + q ] += x * w[ wOff + q ];
			}
		}
	}

	private void im2col( float[] input ){
		int planeSize = inWidth * inHeight;
		java.util.Arrays.fill( col.data, 0f );

		for (int ch = 0; ch < inChannels; ch++){
			int inOff = ch * planeSize;
			for (int ky = 0; ky < kernel; ky++){
				for (int kx = 0; kx < kernel; kx++){

					int row = (ch * kernel + ky) * kernel + kx;
					int rowOff = row * col.cols;

					for (int oy = 0; oy < outHeight; oy++){
						int iy = oy * stride + ky;
						if (iy >= inHeight) continue;
						int inRowBase = inOff + iy * inWidth;
						int outRowBase = oy * outWidth;

						for (int ox = 0; ox < outWidth; ox++){
							int ix = ox * stride + kx;
							if (ix >= inWidth) continue;
							col.data[ rowOff + outRowBase + ox ] = input[ inRowBase + ix ];
						}
					}
				}
			}
		}
	}

	public void backward( float[] input, Tensor gradOut, float[] gradIn ){
		im2col( input );

		int outLen = outputSize();
		for (int i = 0; i < outLen; i++){
			if (preAct[ i ] <= 0f ) gradOut.data[ i ] = 0f;
		}

		int positions = outWidth * outHeight;
		float[] gw = gW.data, c = col.data, go = gradOut.data;
		for (int k = 0; k < W.rows; k++){
			int colOff = k * col.cols;
			int wOff = k * outChannels;
			for (int p = 0; p < positions; p++){
				float x = c[ colOff + p ];
				if (x == 0f) continue;
				int gOff = p * outChannels;
				for (int q = 0; q < outChannels; q++) gw[ wOff + q ] += x * go[ gOff + q ];
			}
		}

		if (gradIn != null) inputGradient( go, gradIn );

		for (int q = 0; q < outChannels; q++){
			float sum = 0;
			for (int p = 0; p < outWidth * outHeight; p++){
				sum += gradOut.data[ p * outChannels + q ];
			}
			gb.data[ q ] += sum;
		}
	}

	private void inputGradient( float[] go, float[] gradIn ){
		java.util.Arrays.fill( gradIn, 0f );
		int planeSize = inWidth * inHeight;
		float[] w = W.data;
		for (int p = 0; p < outWidth * outHeight; p++){
			int gOff = p * outChannels;
			int oy = (p / outWidth) * stride;
			int ox = (p % outWidth) * stride;
			for (int ch = 0; ch < inChannels; ch++){
				for (int ky = 0; ky < kernel; ky++){
					int iy = oy + ky;
					if (iy >= inHeight) continue;
					for (int kx = 0; kx < kernel; kx++){
						int ix = ox + kx;
						if (ix >= inWidth) continue;
						int wOff = ((ch * kernel + ky) * kernel + kx) * outChannels;
						float sum = 0f;
						for (int q = 0; q < outChannels; q++) sum += go[ gOff + q ] * w[ wOff + q ];
						gradIn[ ch * planeSize + iy * inWidth + ix ] += sum;
					}
				}
			}
		}
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