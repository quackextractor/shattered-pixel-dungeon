package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

final class ClRecurrent implements AutoCloseable {

	private final ClDevice cl;
	private final Dense[] heads;
	private final ClParameters params;
	private final long kGates, kCell, kLinear, kCellBack, kInGrad, kLstmW, kDenseW, kDenseIn, kSum, kTranspose, kPack, kUnpack;

	final int in, s, gates, K;

	private final long W, WT, b, Wh, WhT, bh, gW, gb, gWh, gbh;

	private int capacityRows, capacityChunks, weightsVersion = -1;
	private java.nio.FloatBuffer hHost, cHost, hRead, cRead;
	private long h0, c0, gateBuf, cout, hout, logits, dLogits, dhHeads, gg, dPrev, dCell;
	private float[] logitsHost, zeros;

	ClRecurrent( ClDevice cl, Network net, ClParameters params ){
		this.cl = cl;
		this.params = params;
		this.heads = new Dense[]{ net.actionHead, net.slotHead, net.targetHead, net.valueHead };
		kGates = cl.kernel( "lstm_gates_wt" );
		kCell = cl.kernel( "lstm_cell" );
		kLinear = cl.kernel( "dense_forward_linear" );
		kCellBack = cl.kernel( "lstm_cell_backward" );
		kInGrad = cl.kernel( "lstm_input_grad" );
		kLstmW = cl.kernel( "lstm_weight_grad" );
		kDenseW = cl.kernel( "dense_weight_grad" );
		kDenseIn = cl.kernel( "dense_input_grad" );
		kSum = cl.kernel( "sum_rows" );
		kTranspose = cl.kernel( "transpose" );
		kPack = cl.kernel( "pack_columns" );
		kUnpack = cl.kernel( "unpack_columns" );

		in = net.memory.inputSize;
		s = net.memory.size;
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
		WhT = cl.floats( (long) s * K );
		bh = cl.floats( K );
		gWh = cl.floats( (long) s * K );
		gbh = cl.floats( K );
	}

	float[] forward( int steps, int chunks, float[] state0, long X ){
		return forward( steps, chunks, state0, X, null );
	}

	float[] forward( int steps, int chunks, float[] state0, long X, float[] finalState ){
		reserve( steps * chunks, chunks );
		int rows = steps * chunks;
		if (weightsVersion != params.version()){
			uploadWeights();
			weightsVersion = params.version();
		}
		hHost.clear().limit( chunks * s );
		cHost.clear().limit( chunks * s );
		for (int i = 0; i < chunks; i++){
			hHost.put( state0, i * 2 * s, s );
			cHost.put( state0, i * 2 * s + s, s );
		}
		cl.writeAsync( h0, hHost.flip() );
		cl.writeAsync( c0, cHost.flip() );

		for (int t = 0; t < steps; t++){
			cl.runGroups( kGates, (long) chunks * gates, X, h0, hout, WT, b, gateBuf, t, chunks, in, s );
			cl.run( kCell, (long) chunks * s, gateBuf, c0, cout, hout, t, chunks, s );
		}
		cl.run( kLinear, (long) rows * K, hout, Wh, bh, logits, s, K );
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

	void backward( int steps, int chunks, float[] dLogitsHost, long X, long dX ){
		int rows = steps * chunks;
		cl.write( dLogits, dLogitsHost );
		cl.write( dPrev, zeros );
		cl.write( dCell, zeros );

		cl.run( kDenseIn, (long) rows * s, dLogits, WhT, dhHeads, s, s, K );
		cl.run( kDenseW, (long) s * K, hout, dLogits, gWh, rows, s, K );
		cl.run( kSum, K, dLogits, gbh, rows, K );

		for (int t = steps - 1; t >= 0; t--){
			cl.run( kCellBack, (long) chunks * s, gateBuf, c0, cout, dhHeads, dPrev, dCell, gg, t, chunks, s );
			cl.run( kInGrad, (long) chunks * ( in + s ), gg, WT, dX, dPrev, t, chunks, in, s );
		}
		cl.run( kLstmW, (long) ( in + s ) * gates, X, h0, hout, gg, gW, rows, chunks, in, s );
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
		cl.run( kTranspose, (long) s * K, Wh, WhT, s, K );
	}

	private void reserve( int rows, int chunks ){
		if (logitsHost == null || logitsHost.length != rows * K) logitsHost = new float[ rows * K ];
		if (zeros == null || zeros.length != chunks * s) zeros = new float[ chunks * s ];
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
		dPrev = cl.floats( (long) chunks * s );
		dCell = cl.floats( (long) chunks * s );
		gateBuf = cl.floats( (long) rows * gates );
		gg = cl.floats( (long) rows * gates );
		cout = cl.floats( (long) rows * s );
		hout = cl.floats( (long) rows * s );
		dhHeads = cl.floats( (long) rows * s );
		logits = cl.floats( (long) rows * K );
		dLogits = cl.floats( (long) rows * K );
	}

	private void release(){
		if (capacityRows == 0) return;
		cl.release( h0, c0, dPrev, dCell, gateBuf, gg, cout, hout, dhHeads, logits, dLogits );
		capacityRows = 0;
	}

	@Override
	public void close(){
		release();
		cl.release( WT, Wh, WhT, bh, gWh, gbh );
	}
}
