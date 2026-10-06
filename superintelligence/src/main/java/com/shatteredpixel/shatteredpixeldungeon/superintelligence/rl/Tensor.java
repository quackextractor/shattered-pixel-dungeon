package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

/**
 * A flat float buffer with the small amount of arithmetic a trainer actually needs.
 *
 * Every buffer here is owned by one layer or one rollout slot and reused across steps. A training
 * sweep touches tens of millions of floats, so the difference between reusing a buffer and
 * allocating one per step is the difference between a sweep that finishes and one that spends most
 * of its time in the garbage collector.
 *
 * All operations are row-major and unrolled over a caller-supplied output buffer rather than
 * returning new arrays.
 */
public class Tensor {

	public final float[] data;

	/** Leading dimension: rows of {@code cols}. */
	public final int rows;

	public final int cols;

	public Tensor( int rows, int cols ){
		this.data = new float[ rows * cols ];
		this.rows = rows;
		this.cols = cols;
	}

	public Tensor( int size ){
		this( 1, size );
	}

	public int size(){
		return data.length;
	}

	public float get( int row, int col ){
		return data[ row * cols + col ];
	}

	public void set( int row, int col, float v ){
		data[ row * cols + col] = v;
	}

	public void fill( float v ){
		java.util.Arrays.fill( data, v );
	}

	/** out = this + other, elementwise. */
	public void add( Tensor other, float scale ){
		float[] a = data;
		float[] b = other.data;
		for (int i = 0; i < a.length; i++) a[ i ] += b[ i ] * scale;
	}

	/** out = this * scale, elementwise. */
	public void scale( float s ){
		for (int i = 0; i < data.length; i++) data[ i ] *= s;
	}

	/**
	 * Sum of squares of every element.
	 *
	 * Accumulated in double, and that matters: a network this size has millions of gradient entries
	 * and the squares span many orders of magnitude, so a float accumulator loses the small ones
	 * entirely. Global gradient clipping depends on this being right - it is the denominator of the
	 * scale applied to every parameter.
	 */
	public double sumOfSquares(){
		double sum = 0;
		float[] d = data;
		for (int i = 0; i < d.length; i++){
			double v = d[ i ];
			sum += v * v;
		}
		return sum;
	}

	/** out += other * scale, elementwise. */
	public void addScaled( Tensor other, float scale ){
		float[] a = data;
		float[] b = other.data;
		for (int i = 0; i < a.length; i++) a[ i ] += b[ i ] * scale;
	}

	public float sum(){
		float s = 0;
		for (float v : data) s += v;
		return s;
	}

	public float dot( Tensor other ){
		float s = 0;
		for (int i = 0; i < data.length; i++) s += data[ i ] * other.data[ i ];
		return s;
	}

	/**
	 * Row-major matrix multiply: out(1 x n) = x(1 x m) * W(m x n) + bias(1 x n).
	 *
	 * The bias is folded into an optional extra row of {@code W} rather than a separate vector,
	 * which keeps the inner loop to one array and one multiply-add per element.
	 */
	public void matmul( Tensor W, Tensor bias, Tensor out ){
		java.util.Arrays.fill( out.data, 0f );
		for (int m = 0; m < cols; m++){
			float x = data[ m ];
			if (x == 0f) continue;
			int wOff = m * W.cols;
			float[] o = out.data;
			for (int n = 0; n < W.cols; n++){
				o[ n ] += x * W.data[ wOff + n ];
			}
		}
		if (bias != null){
			for (int n = 0; n < out.cols; n++) out.data[ n ] += bias.data[ n ];
		}
	}

	/** Fills with N(0, scale) using Box-Muller. */
	public void randomNormal( Random rng, float scale ){
		for (int i = 0; i < data.length; i += 2){
			float u1 = Math.max( 1e-7f, rng.nextFloat() );
			float u2 = rng.nextFloat();
			float mag = (float) (scale * Math.sqrt( -2 * Math.log( u1 ) ));
			data[ i ] = mag * (float) Math.cos( 2 * Math.PI * u2 );
			if (i + 1 < data.length){
				data[ i + 1 ] = mag * (float) Math.sin( 2 * Math.PI * u2 );
			}
		}
	}

	public void copyFrom( Tensor other ){
		System.arraycopy( other.data, 0, data, 0, Math.min( data.length, other.data.length ) );
	}

	@Override
	public String toString(){
		return "Tensor(" + rows + "x" + cols + ")";
	}
}