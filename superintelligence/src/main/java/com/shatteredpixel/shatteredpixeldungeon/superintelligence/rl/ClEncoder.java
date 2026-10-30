package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

final class ClEncoder implements AutoCloseable {

	private static final int MAX_CHANNELS = 32, WEIGHT_SLICES = 40, ROW_SLICES = 4;

	private final ClDevice cl;
	private final Network net;
	private final long kConcat, kTanhBack, kRelu, kSum, kConvRows, kConvWeight, kIm2col, kCol2im, kBias, kSlicesTanh, kColumnSum;

	final int cells, packedBytes, features, extra, hidden, trunkIn;
	private final int area, convOut, convPatch, reducePatch, reducePositions;

	private final long planeLevels;
	private final long convW, convB, reduceW, reduceB, trunkW, trunkB;
	private final long gConvW, gConvB, gReduceW, gReduceB, gTrunkW, gTrunkB;

	private int capacity;
	private long slicePartial, sliceCapacity;
	private long packed, extras, convPre, col, reducePre, concat, dReduce, dConv, partial;
	long hiddenOut, dHidden;
	private ByteBuffer packedHost;
	private FloatBuffer extrasHost;

	ClEncoder( ClDevice cl, Network net, ClParameters params ){
		this.cl = cl;
		this.net = net;
		if (net.conv.outChannels > MAX_CHANNELS || net.conv.outChannels % 4 != 0){
			throw new IllegalStateException( "the OpenCL encoder needs a first layer of at most " + MAX_CHANNELS + " channels, a multiple of 4" );
		}
		kConcat = cl.kernel( "build_concat" );
		kTanhBack = cl.kernel( "tanh_backward" );
		kRelu = cl.kernel( "relu_mask" );
		kSum = cl.kernel( "sum_rows" );
		kConvRows = cl.kernel( "conv_packed_rows" );
		kConvWeight = cl.kernel( "conv_packed_weight" );
		kIm2col = cl.kernel( "im2col" );
		kCol2im = cl.kernel( "col2im" );
		kBias = cl.kernel( "add_bias" );
		kSlicesTanh = cl.kernel( "slices_bias_tanh" );
		kColumnSum = cl.kernel( "column_sum" );

		cells = net.gridLength();
		area = cells / GridChannel.fractionalPlanes().length;
		packedBytes = Transition.packedGridBytes( cells );
		features = net.reduce.outputSize();
		extra = net.extraInputs;
		hidden = net.trunk.out;
		trunkIn = net.trunk.in;
		convOut = net.conv.outputSize();
		convPatch = net.conv.W.rows;
		reducePatch = net.reduce.W.rows;
		reducePositions = net.reduce.outWidth * net.reduce.outHeight;

		boolean[] fractional = GridChannel.fractionalPlanes();
		int[] levels = new int[ fractional.length ];
		int at = ( cells + 7 ) >>> 3;
		for (int p = 0; p < fractional.length; p++){
			levels[ p ] = fractional[ p ] ? at : -1;
			if (fractional[ p ]) at += area;
		}
		planeLevels = cl.floats( levels.length );
		cl.write( planeLevels, levels );

		convW = params.weight( 0 );
		convB = params.bias( 0 );
		reduceW = params.weight( 1 );
		reduceB = params.bias( 1 );
		trunkW = params.weight( 2 );
		trunkB = params.bias( 2 );
		gConvW = params.weightGrad( 0 );
		gConvB = params.biasGrad( 0 );
		gReduceW = params.weightGrad( 1 );
		gReduceB = params.biasGrad( 1 );
		gTrunkW = params.weightGrad( 2 );
		gTrunkB = params.biasGrad( 2 );
	}

	long packed(){
		return packed;
	}

	long extras(){
		return extras;
	}

	ByteBuffer packedStage( int rows ){
		reserve( rows );
		packedHost.clear().limit( rows * packedBytes );
		return packedHost;
	}

	FloatBuffer extrasStage( int rows ){
		reserve( rows );
		extrasHost.clear().limit( rows * extra );
		return extrasHost;
	}

	void forwardInference( int rows ){
		cl.writeAsync( packed, packedHost.rewind() );
		cl.writeAsync( extras, extrasHost.rewind() );
		forward( rows );
	}

	void forward( int rows ){
		Conv2D c = net.conv, r = net.reduce;
		cl.run( kConvRows, (long) rows * c.outWidth * c.outHeight, packed, planeLevels, convW, convB, convPre, packedBytes, area,
				(float) GridChannel.LEVELS, c.inChannels, c.inWidth, c.outChannels, c.kernel, c.stride, c.outWidth, c.outHeight );
		int reduceRows = rows * reducePositions;
		cl.run( kIm2col, (long) reduceRows * reducePatch, convPre, col,
				r.inChannels, r.inWidth, r.inHeight, r.kernel, r.stride, r.outWidth, r.outHeight, 1, reduceRows );
		gemm( col, reduceW, reducePre, reduceRows, r.outChannels, reducePatch, 1, reduceRows, r.outChannels, 1, r.outChannels, 1 );
		cl.run( kBias, (long) rows * features, reducePre, reduceB, r.outChannels );
		cl.run( kConcat, (long) rows * trunkIn, reducePre, extras, concat, features, extra );
		int tiles = ( ( rows + 63 ) / 64 ) * ( ( hidden + 63 ) / 64 );
		reserveSlices( (long) ( 240 / tiles + 1 ) * rows * hidden );
		int slices = gemm( concat, trunkW, slicePartial, rows, hidden, trunkIn, trunkIn, 1, hidden, 1, hidden, 240 / tiles + 1 );
		cl.run( kSlicesTanh, (long) rows * hidden, slicePartial, trunkB, hiddenOut, slices, hidden, rows * hidden );
	}

	void backward( int rows ){
		Conv2D c = net.conv, r = net.reduce;
		cl.run( kTanhBack, (long) rows * hidden, hiddenOut, dHidden );
		gemm( concat, dHidden, gTrunkW, trunkIn, hidden, rows, 1, trunkIn, hidden, 1, hidden, 1 );
		cl.run( kSum, hidden, dHidden, gTrunkB, rows, hidden );
		gemm( dHidden, trunkW, dReduce, rows, features, hidden, hidden, 1, 1, hidden, features, 1 );
		cl.run( kRelu, (long) rows * features, reducePre, dReduce );

		int reduceRows = rows * reducePositions;
		reserveSlices( (long) WEIGHT_SLICES * reducePatch * r.outChannels );
		int slices = gemm( col, dReduce, slicePartial, reducePatch, r.outChannels, reduceRows, reduceRows, 1, r.outChannels, 1,
				r.outChannels, WEIGHT_SLICES );
		cl.run( kSum, (long) reducePatch * r.outChannels, slicePartial, gReduceW, slices, reducePatch * r.outChannels );
		biasGrad( dReduce, gReduceB, rows, reducePositions, r.outChannels );

		gemm( reduceW, dReduce, col, reducePatch, reduceRows, r.outChannels, r.outChannels, 1, 1, r.outChannels, reduceRows, 1 );
		cl.run( kCol2im, (long) rows * convOut, col, dConv, r.inChannels, r.inWidth, r.inHeight, r.kernel, r.stride, r.outWidth,
				r.outHeight, reduceRows );
		cl.run( kRelu, (long) rows * convOut, convPre, dConv );

		cl.run( kConvWeight, (long) ROW_SLICES * rows * convPatch, packed, planeLevels, dConv, partial, packedBytes, area,
				(float) GridChannel.LEVELS, c.inChannels, c.inWidth, c.outChannels, c.kernel, c.stride, c.outWidth, c.outHeight,
				ROW_SLICES, rows );
		cl.run( kSum, (long) convPatch * c.outChannels, partial, gConvW, ROW_SLICES * rows, convPatch * c.outChannels );
		biasGrad( dConv, gConvB, rows, c.outWidth * c.outHeight, c.outChannels );
	}

	private void biasGrad( long d, long gb, int rows, int positions, int channels ){
		cl.runSized( kColumnSum, channels, 256, d, gb, rows * positions, channels );
	}

	private int gemm( long a, long b, long c, int M, int N, int K, int sAi, int sAk, int sBk, int sBj, int sCi, int slices ){
		return cl.gemm( a, b, c, M, N, K, sAi, sAk, sBk, sBj, sCi, slices );
	}

	private void reserveSlices( long floats ){
		if (floats <= sliceCapacity) return;
		cl.release( slicePartial );
		slicePartial = cl.floats( floats );
		sliceCapacity = floats;
	}

	void reserve( int rows ){
		if (rows <= capacity) return;
		releaseRows();
		capacity = rows;
		packed = cl.bytes( (long) rows * packedBytes );
		extras = cl.floats( (long) rows * extra );
		convPre = cl.floats( (long) rows * convOut );
		col = cl.floats( (long) rows * reducePositions * reducePatch );
		reducePre = cl.floats( (long) rows * features );
		concat = cl.floats( (long) rows * trunkIn );
		hiddenOut = cl.floats( (long) rows * hidden );
		dHidden = cl.floats( (long) rows * hidden );
		dReduce = cl.floats( (long) rows * features );
		dConv = cl.floats( (long) rows * convOut );
		partial = cl.floats( (long) ROW_SLICES * rows * convPatch * net.conv.outChannels );
		packedHost = BufferUtils.createByteBuffer( rows * packedBytes );
		extrasHost = BufferUtils.createFloatBuffer( rows * extra );
	}

	private void releaseRows(){
		if (capacity == 0) return;
		cl.release( packed, extras, convPre, col, reducePre, concat, hiddenOut, dHidden, dReduce, dConv, partial );
		capacity = 0;
	}

	@Override
	public void close(){
		releaseRows();
		cl.release( planeLevels, slicePartial );
	}
}
