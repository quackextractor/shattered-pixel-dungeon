package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

final class ClEncoder implements AutoCloseable {

	private final ClDevice cl;
	private final Network net;
	private final long kUnpack, kConv, kConcat, kDense, kTanhBack, kRelu, kDenseW, kDenseIn, kSum, kConvW, kConvIn, kTranspose;

	final int cells, packedBytes, features, extra, hidden, trunkIn;
	private final int area, convOut, convPatch, reducePatch;

	private final long planeLevels;
	private final long convW, convB, reduceW, reduceB, trunkW, trunkB, trunkWT;
	private final long gConvW, gConvB, gReduceW, gReduceB, gTrunkW, gTrunkB;

	private int capacity;
	private long packed, extras, grid, convPre, reducePre, concat, dReduce, dConv, partial, partialBias;
	long hiddenOut, dHidden;
	private ByteBuffer packedHost;
	private FloatBuffer extrasHost;

	ClEncoder( ClDevice cl, Network net, ClParameters params ){
		this.cl = cl;
		this.net = net;
		kUnpack = cl.kernel( "unpack_grid" );
		kConv = cl.kernel( "conv_forward" );
		kConcat = cl.kernel( "build_concat" );
		kDense = cl.kernel( "dense_forward_tanh" );
		kTanhBack = cl.kernel( "tanh_backward" );
		kRelu = cl.kernel( "relu_mask" );
		kDenseW = cl.kernel( "dense_weight_grad" );
		kDenseIn = cl.kernel( "dense_input_grad" );
		kSum = cl.kernel( "sum_rows" );
		kConvW = cl.kernel( "conv_weight_partial" );
		kConvIn = cl.kernel( "conv_input_grad" );
		kTranspose = cl.kernel( "transpose" );

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
		trunkWT = cl.floats( net.trunk.W.data.length );
		gConvW = params.weightGrad( 0 );
		gConvB = params.biasGrad( 0 );
		gReduceW = params.weightGrad( 1 );
		gReduceB = params.biasGrad( 1 );
		gTrunkW = params.weightGrad( 2 );
		gTrunkB = params.biasGrad( 2 );
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

	void forward( int rows ){
		cl.run( kTranspose, (long) trunkIn * hidden, trunkW, trunkWT, trunkIn, hidden );
		cl.write( packed, packedHost.rewind() );
		cl.write( extras, extrasHost.rewind() );

		Conv2D c = net.conv, r = net.reduce;
		cl.run( kUnpack, (long) rows * cells, packed, planeLevels, grid, cells, area, packedBytes, (float) GridChannel.LEVELS );
		cl.run( kConv, (long) rows * convOut, grid, convW, convB, convPre,
				c.inChannels, c.inWidth, c.inHeight, c.outChannels, c.kernel, c.stride, c.outWidth, c.outHeight, 0 );
		cl.run( kConv, (long) rows * features, convPre, reduceW, reduceB, reducePre,
				r.inChannels, r.inWidth, r.inHeight, r.outChannels, r.kernel, r.stride, r.outWidth, r.outHeight, 1 );
		cl.run( kConcat, (long) rows * trunkIn, reducePre, extras, concat, features, extra );
		cl.run( kDense, (long) rows * hidden, concat, trunkW, trunkB, hiddenOut, trunkIn, hidden );
	}

	void backward( int rows ){
		Conv2D c = net.conv, r = net.reduce;
		cl.run( kTanhBack, (long) rows * hidden, hiddenOut, dHidden );
		cl.run( kDenseW, (long) trunkIn * hidden, concat, dHidden, gTrunkW, rows, trunkIn, hidden );
		cl.run( kSum, hidden, dHidden, gTrunkB, rows, hidden );
		cl.run( kDenseIn, (long) rows * features, dHidden, trunkWT, dReduce, features, trunkIn, hidden );
		cl.run( kRelu, (long) rows * features, reducePre, dReduce );

		cl.run( kConvW, (long) rows * reducePatch * r.outChannels, convPre, dReduce, partial,
				r.inChannels, r.inWidth, r.inHeight, r.outChannels, r.kernel, r.stride, r.outWidth, r.outHeight, 1 );
		cl.run( kSum, (long) reducePatch * r.outChannels, partial, gReduceW, rows, reducePatch * r.outChannels );
		cl.run( kSum, (long) rows * r.outChannels, dReduce, partialBias, r.outWidth * r.outHeight, r.outChannels );
		cl.run( kSum, r.outChannels, partialBias, gReduceB, rows, r.outChannels );

		cl.run( kConvIn, (long) rows * convOut, dReduce, reduceW, dConv,
				r.inChannels, r.inWidth, r.inHeight, r.outChannels, r.kernel, r.stride, r.outWidth, r.outHeight );
		cl.run( kRelu, (long) rows * convOut, convPre, dConv );

		cl.run( kConvW, (long) rows * convPatch * c.outChannels, grid, dConv, partial,
				c.inChannels, c.inWidth, c.inHeight, c.outChannels, c.kernel, c.stride, c.outWidth, c.outHeight, 0 );
		cl.run( kSum, (long) convPatch * c.outChannels, partial, gConvW, rows, convPatch * c.outChannels );
		cl.run( kSum, (long) rows * c.outChannels, dConv, partialBias, c.outWidth * c.outHeight, c.outChannels );
		cl.run( kSum, c.outChannels, partialBias, gConvB, rows, c.outChannels );
	}

	private void reserve( int rows ){
		if (rows <= capacity) return;
		releaseRows();
		capacity = rows;
		packed = cl.bytes( (long) rows * packedBytes );
		extras = cl.floats( (long) rows * extra );
		grid = cl.floats( (long) rows * cells );
		convPre = cl.floats( (long) rows * convOut );
		reducePre = cl.floats( (long) rows * features );
		concat = cl.floats( (long) rows * trunkIn );
		hiddenOut = cl.floats( (long) rows * hidden );
		dHidden = cl.floats( (long) rows * hidden );
		dReduce = cl.floats( (long) rows * features );
		dConv = cl.floats( (long) rows * convOut );
		partial = cl.floats( (long) rows * Math.max( convPatch * net.conv.outChannels, reducePatch * net.reduce.outChannels ));
		partialBias = cl.floats( (long) rows * Math.max( net.conv.outChannels, net.reduce.outChannels ));
		packedHost = BufferUtils.createByteBuffer( rows * packedBytes );
		extrasHost = BufferUtils.createFloatBuffer( rows * extra );
	}

	private void releaseRows(){
		if (capacity == 0) return;
		cl.release( packed, extras, grid, convPre, reducePre, concat, hiddenOut, dHidden, dReduce, dConv, partial, partialBias );
		capacity = 0;
	}

	@Override
	public void close(){
		releaseRows();
		cl.release( planeLevels, trunkWT );
	}
}
