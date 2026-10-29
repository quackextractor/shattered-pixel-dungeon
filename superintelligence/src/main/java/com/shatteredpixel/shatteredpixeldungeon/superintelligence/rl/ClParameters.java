package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

final class ClParameters implements AutoCloseable {

	private static final int NORM_GROUPS = 4096;

	private final ClDevice cl;
	private final NetworkWeights net;
	private final long kSquares, kAdam;

	private final int[] offset, length;
	private final int total;
	private final long params, grads, m, v, partial;
	private final long[] paramView, gradView;
	private final float[] partialHost = new float[ NORM_GROUPS ];

	private float lastScale = 1f;

	private int version;

	int version(){
		return version;
	}

	ClParameters( ClDevice cl, NetworkWeights net ){
		this.cl = cl;
		this.net = net;
		kSquares = cl.kernel( "sum_squares" );
		kAdam = cl.kernel( "adam" );

		float[][] p = paramArrays();
		offset = new int[ p.length ];
		length = new int[ p.length ];
		int align = cl.alignFloats(), at = 0;
		for (int i = 0; i < p.length; i++){
			offset[ i ] = at;
			length[ i ] = p[ i ].length;
			at += ( p[ i ].length + align - 1 ) / align * align;
		}
		total = at;
		params = cl.floats( total );
		grads = cl.floats( total );
		m = cl.floats( total );
		v = cl.floats( total );
		partial = cl.floats( NORM_GROUPS );
		cl.write( grads, 0, new float[ total ] );
		paramView = new long[ p.length ];
		gradView = new long[ p.length ];
		for (int i = 0; i < p.length; i++){
			paramView[ i ] = cl.view( params, offset[ i ], length[ i ] );
			gradView[ i ] = cl.view( grads, offset[ i ], length[ i ] );
		}
	}

	long weight( int layer ){ return paramView[ 2 * layer ]; }
	long bias( int layer ){ return paramView[ 2 * layer + 1 ]; }
	long weightGrad( int layer ){ return gradView[ 2 * layer ]; }
	long biasGrad( int layer ){ return gradView[ 2 * layer + 1 ]; }

	void upload(){
		version++;
		float[][] p = paramArrays(), mm = momentArrays( true ), vv = momentArrays( false );
		for (int i = 0; i < p.length; i++){
			cl.write( params, offset[ i ], p[ i ] );
			cl.write( m, offset[ i ], mm[ i ] );
			cl.write( v, offset[ i ], vv[ i ] );
		}
	}

	void uploadParameters(){
		version++;
		float[][] p = paramArrays();
		for (int i = 0; i < p.length; i++) cl.write( params, offset[ i ], p[ i ] );
	}

	void download(){
		float[][] p = paramArrays(), mm = momentArrays( true ), vv = momentArrays( false ), g = net.gradientArrays();
		for (int i = 0; i < p.length; i++){
			cl.read( params, offset[ i ], p[ i ] );
			cl.read( m, offset[ i ], mm[ i ] );
			cl.read( v, offset[ i ], vv[ i ] );
			cl.read( grads, offset[ i ], g[ i ] );
			for (int k = 0; k < g[ i ].length; k++) g[ i ][ k ] *= lastScale;
		}
	}

	float step( int count, float maxNorm ){
		cl.run( kSquares, NORM_GROUPS, grads, partial, total, NORM_GROUPS );
		cl.read( partial, partialHost );
		double sum = 0;
		for (float s : partialHost) sum += s;
		float norm = (float) ( Math.sqrt( sum ) / count );
		lastScale = 1f / count;
		if (maxNorm > 0f && norm > maxNorm && norm > 0f) lastScale *= maxNorm / norm;

		int step = net.adamSteps() + 1;
		net.adamSteps( step );
		float bias1 = 1f - (float) Math.pow( net.beta1, step );
		float bias2 = 1f - (float) Math.pow( net.beta2, step );
		version++;
		cl.run( kAdam, total, params, grads, m, v, net.learningRate, net.beta1, net.beta2, net.adamEps, lastScale, bias1, bias2 );
		return norm;
	}

	private float[][] paramArrays(){
		NetworkWeights.Layer[] layers = net.layers();
		float[][] out = new float[ 2 * layers.length ][];
		for (int i = 0; i < layers.length; i++){
			out[ 2 * i ] = layers[ i ].weights;
			out[ 2 * i + 1 ] = layers[ i ].bias;
		}
		return out;
	}

	private float[][] momentArrays( boolean first ){
		NetworkWeights.Moments[] moments = net.moments();
		float[][] out = new float[ 2 * moments.length ][];
		for (int i = 0; i < moments.length; i++){
			out[ 2 * i ] = first ? moments[ i ].mW : moments[ i ].vW;
			out[ 2 * i + 1 ] = first ? moments[ i ].mb : moments[ i ].vb;
		}
		return out;
	}

	@Override
	public void close(){
		cl.release( paramView );
		cl.release( gradView );
		cl.release( params, grads, m, v, partial );
	}
}
