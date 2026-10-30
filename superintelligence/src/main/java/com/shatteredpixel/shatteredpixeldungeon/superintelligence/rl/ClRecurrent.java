package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

final class ClRecurrent implements AutoCloseable {

	private final ClDevice cl;
	private final Dense[] heads;
	private final ClParameters params;
	private final long kForward, kBackward, kBias, kSum, kTranspose, kPack, kUnpack;

	final int in, s, gates, K;

	private final long W, WT, b, Wh, bh, gW, gb, gWh, gbh;

	private int capacityRows, capacityChunks, weightsVersion = -1;
	private java.nio.FloatBuffer hHost, cHost, hRead, cRead;
	private long h0, c0, gateBuf, cout, hout, hprev, logits, dLogits, dhHeads, gg;
	private float[] logitsHost;

	ClRecurrent( ClDevice cl, Network net, ClParameters params ){
		this.cl = cl;
		this.params = params;
		this.heads = new Dense[]{ net.actionHead, net.slotHead, net.targetHead, net.valueHead };
		kForward = cl.kernel( "lstm_forward_fused" );
		kBackward = cl.kernel( "lstm_backward_fused" );
		kBias = cl.kernel( "add_bias" );
		kSum = cl.kernel( "sum_rows" );
		kTranspose = cl.kernel( "transpose" );
		kPack = cl.kernel( "pack_columns" );
		kUnpack = cl.kernel( "unpack_columns" );

		in = net.memory.inputSize;
		s = net.memory.size;
		if (s > 256) throw new IllegalStateException( "the OpenCL LSTM holds at most 256 cells, the network has " + s );
		gates = 4 * s;
		int k = 0;
		for (Dense h : heads) k += h.out;
		K = k;

		W = params.weight( 3 );
		b = params.bias( 3 );
		gW = params.weightGrad( 3 );
		gb = params.biasGrad( 3 );
		WT = cl.floats( net.memory.W.data.length );
		Wh = cl.floats( (long) s * K );
		bh = cl.floats( K );
		gWh = cl.floats( (long) s * K );
		gbh = cl.floats( K );
	}

	long h0(){ return h0; }
	long c0(){ return c0; }
	long logits(){ return logits; }
	long dLogits(){ return dLogits; }

	void forward( int steps, int chunks, long X ){
		reserve( steps * chunks, chunks );
		uploadIfChanged();
		run( steps, chunks, X );
	}

	private void uploadIfChanged(){
		if (weightsVersion != params.version()){
			uploadWeights();
			weightsVersion = params.version();
		}
	}

	private void run( int steps, int chunks, long X ){
		int rows = steps * chunks;
		cl.gemm( X, W, gateBuf, rows, gates, in, in, 1, gates, 1, gates, 1 );
		cl.runSized( kForward, chunks, 256, gateBuf, h0, c0, W, b, gateBuf, hout, cout, steps, chunks, in, s );
		cl.gemm( hout, Wh, logits, rows, K, s, s, 1, K, 1, K, 1 );
		cl.run( kBias, (long) rows * K, logits, bh, K );
	}

	float[] forward( int steps, int chunks, float[] state0, long X, float[] finalState ){
		reserve( steps * chunks, chunks );
		uploadIfChanged();
		hHost.clear().limit( chunks * s );
		cHost.clear().limit( chunks * s );
		for (int i = 0; i < chunks; i++){
			hHost.put( state0, i * 2 * s, s );
			cHost.put( state0, i * 2 * s + s, s );
		}
		cl.writeAsync( h0, hHost.flip() );
		cl.writeAsync( c0, cHost.flip() );
		run( steps, chunks, X );
		if (finalState != null){
			hRead.clear().limit( chunks * s );
			cRead.clear().limit( chunks * s );
			cl.readAsync( hout, ( steps - 1 ) * chunks * s, hRead );
			cl.readAsync( cout, ( steps - 1 ) * chunks * s, cRead );
		}
		cl.read( logits, logitsHost );
		if (finalState != null){
			for (int i = 0; i < chunks; i++){
				hRead.get( i * s, finalState, i * 2 * s, s );
				cRead.get( i * s, finalState, i * 2 * s + s, s );
			}
		}
		return logitsHost;
	}

	void backward( int steps, int chunks, long X, long dX ){
		int rows = steps * chunks;
		cl.gemm( dLogits, Wh, dhHeads, rows, s, K, K, 1, 1, K, s, 1 );
		cl.gemm( hout, dLogits, gWh, s, K, rows, 1, s, K, 1, K, 1 );
		cl.run( kSum, K, dLogits, gbh, rows, K );

		cl.runSized( kBackward, chunks, 256, gateBuf, c0, cout, dhHeads, WT, gg, steps, chunks, in, s );
		cl.gemm( gg, W, dX, rows, in, gates, gates, 1, 1, gates, in, 1 );
		cl.gemm( X, gg, gW, in, gates, rows, 1, in, gates, 1, gates, 1 );
		cl.copy( h0, 0, hprev, 0, chunks * s );
		cl.copy( hout, 0, hprev, chunks * s, ( rows - chunks ) * s );
		cl.gemm( hprev, 0, gg, 0, gW, in * gates, s, gates, rows, 1, s, gates, 1, gates, 1 );
		cl.run( kSum, gates, gg, gb, rows, gates );

		for (int h = 0, off = 0; h < heads.length; off += heads[ h ].out, h++){
			cl.run( kUnpack, (long) s * heads[ h ].out, gWh, params.weightGrad( 4 + h ), heads[ h ].out, K, off );
			cl.run( kUnpack, heads[ h ].out, gbh, params.biasGrad( 4 + h ), heads[ h ].out, K, off );
		}
	}

	private void uploadWeights(){
		cl.run( kTranspose, (long) ( in + s ) * gates, W, WT, in + s, gates );
		for (int h = 0, off = 0; h < heads.length; off += heads[ h ].out, h++){
			cl.run( kPack, (long) s * heads[ h ].out, params.weight( 4 + h ), Wh, heads[ h ].out, K, off );
			cl.run( kPack, heads[ h ].out, params.bias( 4 + h ), bh, heads[ h ].out, K, off );
		}
	}

	void reserve( int rows, int chunks ){
		if (logitsHost == null || logitsHost.length != rows * K) logitsHost = new float[ rows * K ];
		if (rows <= capacityRows && chunks <= capacityChunks) return;
		int grownRows = Math.max( rows, capacityRows ), grownChunks = Math.max( chunks, capacityChunks );
		release();
		capacityRows = rows = grownRows;
		capacityChunks = chunks = grownChunks;
		hHost = org.lwjgl.BufferUtils.createFloatBuffer( chunks * s );
		cHost = org.lwjgl.BufferUtils.createFloatBuffer( chunks * s );
		hRead = org.lwjgl.BufferUtils.createFloatBuffer( chunks * s );
		cRead = org.lwjgl.BufferUtils.createFloatBuffer( chunks * s );
		h0 = cl.floats( (long) chunks * s );
		c0 = cl.floats( (long) chunks * s );
		gateBuf = cl.floats( (long) rows * gates );
		gg = cl.floats( (long) rows * gates );
		cout = cl.floats( (long) rows * s );
		hout = cl.floats( (long) rows * s );
		hprev = cl.floats( (long) rows * s );
		dhHeads = cl.floats( (long) rows * s );
		logits = cl.floats( (long) rows * K );
		dLogits = cl.floats( (long) rows * K );
	}

	private void release(){
		if (capacityRows == 0) return;
		cl.release( h0, c0, gateBuf, gg, cout, hout, hprev, dhHeads, logits, dLogits );
		capacityRows = 0;
	}

	@Override
	public void close(){
		release();
		cl.release( WT, Wh, bh, gWh, gbh );
	}
}
