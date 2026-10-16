package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

public class Tensor {

	public final float[] data;

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

	public void add( Tensor other, float scale ){
		float[] a = data;
		float[] b = other.data;
		for (int i = 0; i < a.length; i++) a[ i ] += b[ i ] * scale;
	}

	public void scale( float s ){
		for (int i = 0; i < data.length; i++) data[ i ] *= s;
	}

	public double sumOfSquares(){
		double sum = 0;
		float[] d = data;
		for (int i = 0; i < d.length; i++){
			double v = d[ i ];
			sum += v * v;
		}
		return sum;
	}

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