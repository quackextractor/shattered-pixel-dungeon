package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

/**
 * A 2D convolution over the observation planes, with stride, ReLU and Adam state.
 *
 * research.md: "Spatial Vision (CNN): A Convolutional Neural Network is the best way to process
 * the dungeon grid."
 *
 * Only the first layer is convolutional. Deeper convolutions buy translation invariance that this
 * problem does not want: dungeon rooms are not the same shape, and what matters is the relationship
 * between the hero, the walls and the stairs rather than a reusable local motif. A single
 * convolution that mixes every plane locally, followed by a fully connected stack over the
 * flattened result, gives the network local spatial features and global reasoning without the
 * parameter cost of a real conv backbone.
 *
 * Implemented as an im2col GEMM rather than nested loops over input windows, so the hot path is a
 * single matrix multiply per sample.
 */
public class Conv2D {

	public final Tensor W;
	public final Tensor b;

	final Tensor mW, vW, mb, vb;
	final Tensor gW, gb;

	public final int inChannels;
	public final int inSize;
	public final int kernel;
	public final int stride;
	public final int outSize;
	public final int outChannels;

	/** im2col scratch: (kernel*kernel*inChannels) x (outSize*outSize). */
	private final Tensor col;

	public Conv2D( int inChannels, int inSize, int outChannels, int kernel, int stride, Random rng ){
		this.inChannels = inChannels;
		this.inSize = inSize;
		this.kernel = kernel;
		this.stride = stride;
		this.outChannels = outChannels;

		this.outSize = (inSize - kernel) / stride + 1;

		int patch = kernel * kernel * inChannels;
		this.W = new Tensor( patch, outChannels );
		this.b = new Tensor( 1, outChannels );

		this.mW = new Tensor( patch, outChannels );
		this.vW = new Tensor( patch, outChannels );
		this.mb = new Tensor( 1, outChannels );
		this.vb = new Tensor( 1, outChannels );

		this.gW = new Tensor( patch, outChannels );
		this.gb = new Tensor( 1, outChannels );

		this.col = new Tensor( patch, outSize * outSize );

		W.randomNormal( rng, (float) Math.sqrt( 2.0 / patch ) );
		b.fill( 0f );
	}

	public int outputSize(){
		return outSize * outSize * outChannels;
	}

	/**
	 * @param input plane-major grid: channel * inSize * inSize
	 * @param out   output, size {@link #outputSize()}
	 */
	public void forward( float[] input, Tensor out ){
		im2col( input );

		java.util.Arrays.fill( out.data, 0f );

		//out = col^T * W + b, computed as a sequence of row outputs
		for (int p = 0; p < outSize * outSize; p++){
			int cOff = p * outChannels;
			for (int q = 0; q < outChannels; q++){
				float sum = b.data[ q ];
				for (int k = 0; k < W.rows; k++){
					sum += col.data[ k * col.cols + p ] * W.data[ k * outChannels + q ];
				}
				out.data[ cOff + q ] = sum;
			}
		}

		for (int i = 0; i < out.data.length; i++){
			if (out.data[ i ] < 0f ) out.data[ i ] = 0f; //ReLU
		}
	}

	private void im2col( float[] input ){
		int planeSize = inSize * inSize;
		java.util.Arrays.fill( col.data, 0f );

		for (int ch = 0; ch < inChannels; ch++){
			int inOff = ch * planeSize;
			for (int ky = 0; ky < kernel; ky++){
				for (int kx = 0; kx < kernel; kx++){
					//column index within the patch, matching the weight row order
					int row = (ch * kernel + ky) * kernel + kx;
					int rowOff = row * col.cols;

					for (int oy = 0; oy < outSize; oy++){
						int iy = oy * stride + ky;
						if (iy >= inSize) continue;
						int inRowBase = inOff + iy * inSize;
						int outRowBase = oy * outSize;

						for (int ox = 0; ox < outSize; ox++){
							int ix = ox * stride + kx;
							if (ix >= inSize) continue;
							col.data[ rowOff + outRowBase + ox ] = input[ inRowBase + ix ];
						}
					}
				}
			}
		}
	}

	/**
	 * Accumulates dL/dW, dL/db and dL/dinput.
	 *
	 * @param gradOut dL/dout, size {@link #outputSize()}
	 * @param gradIn  dL/dinput, cleared and filled by this method
	 */
	public void backward( float[] input, Tensor gradOut, float[] gradIn ){
		java.util.Arrays.fill( gradIn, 0f );

		int planeSize = inSize * inSize;

		//dL/dW and dL/dinput, walked over the same im2col layout used by forward
		for (int p = 0; p < outSize * outSize; p++){
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
							gradIn[ inOff + (p / outSize) * inSize * stride
									+ (p % outSize) * stride + ky ] += g * W.data[ wOff ];
						}
					}
				}
			}
		}

		for (int q = 0; q < outChannels; q++){
			float sum = 0;
			for (int p = 0; p < outSize * outSize; p++){
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