package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import java.util.Random;


public abstract class NetworkWeights {

	public final EnvConfig config;

	public final Conv2D conv;
	public final Conv2D reduce;
	public final LSTM memory;
	public final Dense trunk;
	public final Dense actionHead;
	public final Dense slotHead;
	public final Dense targetHead;
	public final Dense valueHead;

	protected final int extraInputs;

	public float learningRate = 3e-4f;
	public float beta1 = 0.9f;
	public float beta2 = 0.999f;
	public float adamEps = 1e-8f;


	public float gradClip = 0.5f;

	private int adamStep = 0;

	private final Tensor[] gradients;

	public static final int CONV_FILTERS = 24;
	public static final int CONV_KERNEL = 4;
	public static final int CONV_STRIDE = 2;

	public static final int REDUCE_FILTERS = 32;
	public static final int REDUCE_KERNEL = 4;
	public static final int REDUCE_STRIDE = 2;

	protected NetworkWeights( EnvConfig config, Random rng ){
		this.config = config;

		this.extraInputs = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT + HeroEncoder.FEATURES;

		this.conv = new Conv2D( config.spatialChannels(), config.gridWidth, config.gridHeight,
				CONV_FILTERS, CONV_KERNEL, CONV_STRIDE, rng );

		this.reduce = new Conv2D( conv.outChannels, conv.outWidth, conv.outHeight,
				REDUCE_FILTERS, REDUCE_KERNEL, REDUCE_STRIDE, rng );

		int convFeatures = reduce.outputSize();

		this.trunk = new Dense( convFeatures + extraInputs, 256, rng, 1f );
		this.memory = new LSTM( 256, 128, rng );

		this.actionHead = new Dense( 128, Action.size(), rng, 1f, false );
		this.slotHead = new Dense( 128, config.maxSlots, rng, 1f, false );
		this.targetHead = new Dense( 128, ActionMapper.TARGET_COUNT, rng, 1f, false );

		this.valueHead = new Dense( 128, 1, rng, 1f, false );

		this.gradients = new Tensor[] {
				conv.gW, conv.gb,
				reduce.gW, reduce.gb,
				trunk.gW, trunk.gb,
				memory.gW, memory.gb,
				actionHead.gW, actionHead.gb,
				slotHead.gW, slotHead.gb,
				targetHead.gW, targetHead.gb,
				valueHead.gW, valueHead.gb,
		};
	}


	public float[][] gradientArrays(){
		float[][] out = new float[ gradients.length ][];
		for (int i = 0; i < out.length; i++) out[ i ] = gradients[ i ].data;
		return out;
	}

	public float gradientNorm(){
		double sum = 0;
		for (Tensor g : gradients) sum += g.sumOfSquares();
		return (float) Math.sqrt( sum );
	}


	public void scaleGradients( float scale ){
		for (Tensor g : gradients) g.scale( scale );
	}


	public float clipGradients( float maxNorm ){
		float norm = gradientNorm();
		if (maxNorm > 0f && norm > maxNorm && norm > 0f){
			scaleGradients( maxNorm / norm );
		}
		return norm;
	}

	public void zeroGrad(){
		conv.zeroGrad();
		reduce.zeroGrad();
		trunk.zeroGrad();
		memory.zeroGrad();
		actionHead.zeroGrad();
		slotHead.zeroGrad();
		targetHead.zeroGrad();
		valueHead.zeroGrad();
	}


	public void step( float gradScale ){
		adamStep++;
		float s = gradScale;
		conv.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		reduce.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		trunk.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		memory.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		actionHead.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		slotHead.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		targetHead.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
		valueHead.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
	}

	public int adamSteps(){
		return adamStep;
	}


	public void adamSteps( int step ){
		this.adamStep = Math.max( 0, step );
	}


	public long parameterCount(){
		return (long) conv.W.size() + conv.b.size()
				+ reduce.W.size() + reduce.b.size()
				+ trunk.W.size() + trunk.b.size()
				+ memory.W.size() + memory.b.size()
				+ actionHead.W.size() + actionHead.b.size()
				+ slotHead.W.size() + slotHead.b.size()
				+ targetHead.W.size() + targetHead.b.size()
				+ valueHead.W.size() + valueHead.b.size();
	}


	public static class Layer {
		public final String name;
		public final int in;
		public final int out;
		public final float[] weights;
		public final float[] bias;

		public Layer( String name, int in, int out, float[] weights, float[] bias ){
			this.name = name;
			this.in = in;
			this.out = out;
			this.weights = weights;
			this.bias = bias;
		}
	}


	public Layer[] layers(){
		return new Layer[] {
				new Layer( "conv.W", conv.W.rows, conv.W.cols, conv.W.data, conv.b.data ),
				new Layer( "reduce.W", reduce.W.rows, reduce.W.cols, reduce.W.data, reduce.b.data ),
				new Layer( "trunk.W", trunk.W.rows, trunk.W.cols, trunk.W.data, trunk.b.data ),
				new Layer( "memory.W", memory.W.rows, memory.W.cols, memory.W.data, memory.b.data ),
				new Layer( "actionHead.W", actionHead.W.rows, actionHead.W.cols, actionHead.W.data, actionHead.b.data ),
				new Layer( "slotHead.W", slotHead.W.rows, slotHead.W.cols, slotHead.W.data, slotHead.b.data ),
				new Layer( "targetHead.W", targetHead.W.rows, targetHead.W.cols, targetHead.W.data, targetHead.b.data ),
				new Layer( "valueHead.W", valueHead.W.rows, valueHead.W.cols, valueHead.W.data, valueHead.b.data ),
		};
	}


	public void loadLayer( Layer layer ) throws java.io.IOException {
		if (layer.weights.length != layer.in * layer.out){
			throw new java.io.IOException( "checkpoint layer " + layer.name
					+ " has " + layer.weights.length + " weights but its shape is "
					+ layer.in + "x" + layer.out );
		}
		java.util.Map<String, Layer> byName = byName();
		Layer mine = byName.get( layer.name );
		if (mine == null){
			throw new java.io.IOException( "unknown checkpoint layer: " + layer.name );
		}
		if (mine.in != layer.in || mine.out != layer.out){
			throw new java.io.IOException( "checkpoint layer " + layer.name + " is "
					+ layer.in + "x" + layer.out + " but this network expects "
					+ mine.in + "x" + mine.out
					+ ". The checkpoint was trained with a different EnvConfig." );
		}
		System.arraycopy( layer.weights, 0, mine.weights, 0, mine.weights.length );
		System.arraycopy( layer.bias, 0, mine.bias, 0, mine.bias.length );
	}

	private java.util.Map<String, Layer> byName(){
		java.util.Map<String, Layer> map = new java.util.HashMap<>();
		for (Layer l : layers()) map.put( l.name, l );
		return map;
	}


	public static class Moments {
		public final String name;
		public final float[] mW, vW, mb, vb;

		public Moments( String name, float[] mW, float[] vW, float[] mb, float[] vb ){
			this.name = name;
			this.mW = mW;
			this.vW = vW;
			this.mb = mb;
			this.vb = vb;
		}
	}


	public Moments[] moments(){
		return new Moments[] {
				momentsOf( "conv.W", conv.mW, conv.vW, conv.mb, conv.vb ),
				momentsOf( "reduce.W", reduce.mW, reduce.vW, reduce.mb, reduce.vb ),
				momentsOf( "trunk.W", trunk.mW, trunk.vW, trunk.mb, trunk.vb ),
				momentsOf( "memory.W", memory.mW, memory.vW, memory.mb, memory.vb ),
				momentsOf( "actionHead.W", actionHead.mW, actionHead.vW, actionHead.mb, actionHead.vb ),
				momentsOf( "slotHead.W", slotHead.mW, slotHead.vW, slotHead.mb, slotHead.vb ),
				momentsOf( "targetHead.W", targetHead.mW, targetHead.vW, targetHead.mb, targetHead.vb ),
				momentsOf( "valueHead.W", valueHead.mW, valueHead.vW, valueHead.mb, valueHead.vb ),
		};
	}

	private static Moments momentsOf( String name, Tensor mW, Tensor vW, Tensor mb, Tensor vb ){
		return new Moments( name, mW.data, vW.data, mb.data, vb.data );
	}


	public void loadMoments( Moments m ) throws java.io.IOException {
		java.util.Map<String, Moments> mine = new java.util.HashMap<>();
		for (Moments other : moments()) mine.put( other.name, other );

		Moments target = mine.get( m.name );
		if (target == null) throw new java.io.IOException( "unknown moment group: " + m.name );

		if (m.mW.length != target.mW.length || m.vW.length != target.vW.length
				|| m.mb.length != target.mb.length || m.vb.length != target.vb.length){
			throw new java.io.IOException( "moment group " + m.name + " is "
					+ m.mW.length + "/" + m.vW.length + "/" + m.mb.length + "/" + m.vb.length
					+ " but this network expects " + target.mW.length + "/" + target.vW.length
					+ "/" + target.mb.length + "/" + target.vb.length
					+ ". The checkpoint was truncated, or was written by a build that laid Adam out"
					+ " differently." );
		}

		System.arraycopy( m.mW, 0, target.mW, 0, target.mW.length );
		System.arraycopy( m.vW, 0, target.vW, 0, target.vW.length );
		System.arraycopy( m.mb, 0, target.mb, 0, target.mb.length );
		System.arraycopy( m.vb, 0, target.vb, 0, target.vb.length );
	}


	public void copyParametersTo( NetworkWeights target ){
		Layer[] src = layers();
		Layer[] dst = target.layers();
		for (int i = 0; i < src.length; i++){
			System.arraycopy( src[ i ].weights, 0, dst[ i ].weights, 0, src[ i ].weights.length );
			System.arraycopy( src[ i ].bias, 0, dst[ i ].bias, 0, src[ i ].bias.length );
		}
	}


	public void copyParametersFrom( NetworkWeights source ){
		source.copyParametersTo( this );
	}


	public void addGradientsTo( NetworkWeights target ){
		for (int i = 0; i < gradients.length; i++){
			float[] from = gradients[ i ].data;
			float[] into = target.gradients[ i ].data;
			for (int k = 0; k < from.length; k++) into[ k ] += from[ k ];
		}
	}


	public void clearGradients(){
		for (Tensor g : gradients) g.fill( 0f );
	}


	public float[][] gradientTensors(){
		float[][] out = new float[ gradients.length ][];
		for (int i = 0; i < gradients.length; i++) out[ i ] = gradients[ i ].data;
		return out;
	}
}
