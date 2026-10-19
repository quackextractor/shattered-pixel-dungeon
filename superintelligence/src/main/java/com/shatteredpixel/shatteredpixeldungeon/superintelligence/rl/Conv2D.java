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
		im2col( input );

		java.util.Arrays.fill( out.data, 0f );

		for (int p = 0; p < outWidth * outHeight; p++){
			int cOff = p * outChannels;
			for (int q = 0; q < outChannels; q++){
				float sum = b.data[ q ];
				for (int k = 0; k < W.rows; k++){
					sum += col.data[ k * col.cols + p ] * W.data[ k * outChannels + q ];
				}
				out.data[ cOff + q ] = sum;
				preAct[ cOff + q ] = sum;
			}
		}

		for (int i = 0; i < out.data.length; i++){
			if (out.data[ i ] < 0f ) out.data[ i ] = 0f; 
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
		java.util.Arrays.fill( gradIn, 0f );

		int planeSize = inWidth * inHeight;

		int outLen = outputSize();
		for (int i = 0; i < outLen; i++){
			if (preAct[ i ] <= 0f ) gradOut.data[ i ] = 0f;
		}

		for (int p = 0; p < outWidth * outHeight; p++){
			int cOff = p * outChannels;

			for (int ch = 0; ch < inChannels; ch++){
				int inOff = ch * planeSize;
				for (int ky = 0; ky < kernel; ky++){
					for (int kx = 0; kx < kernel; kx++){
						int row = (ch * kernel + ky) * kernel + kx;
						int rowOff = row * col.cols;

						for (int q = 0; q < outChannels; q++){
							float g = gradOut.data[ cOff + q ];
							if (g == 0f) continue;
							int wOff = row * outChannels + q;
							gW.data[ wOff ] += col.data[ rowOff + p ] * g;

							int iy = (p / outWidth) * stride + ky;
							int ix = (p % outWidth) * stride + kx;
							if (iy < inHeight && ix < inWidth){
								gradIn[ inOff + iy * inWidth + ix ] += g * W.data[ wOff ];
							}
						}
					}
				}
			}
		}

		for (int q = 0; q < outChannels; q++){
			float sum = 0;
			for (int p = 0; p < outWidth * outHeight; p++){
				sum += gradOut.data[ p * outChannels + q ];
			}
			gb.data[ q ] += sum;
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