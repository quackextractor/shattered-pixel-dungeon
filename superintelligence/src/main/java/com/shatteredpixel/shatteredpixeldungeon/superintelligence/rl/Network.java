package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import java.util.Random;

/**
 * The actor-critic network: CNN over the grid, LSTM for memory, one head per kind of choice.
 *
 * research.md: "The Network Architecture: CNN + LSTM... This flattened inventory vector will bypass
 * the CNN and be concatenated directly with the spatial data before entering the LSTM memory
 * layer."
 *
 * Concatenation order follows research.md: spatial features, then the inventory vector, then the
 * hero scalars, all into one LSTM cell. The inventory is flat by necessity - a grid has no notion
 * of "which slot" - so it is concatenated rather than pooled.
 *
 * Three action heads plus a value head, because the environment has three kinds of decision and a
 * critic. All three action heads are evaluated every step even though only one is live. That costs
 * one small matmul and removes an entire category of "which head was live" bookkeeping from the
 * rollout loop, which is where such bugs hide.
 */
public class Network {

	public final EnvConfig config;

	public final Conv2D conv;
	public final LSTM memory;
	public final Dense trunk;
	public final Dense actionHead;
	public final Dense slotHead;
	public final Dense targetHead;
	public final Dense valueHead;

	private final int extraInputs;

	// Adam settings, shared by every layer.
	public float learningRate = 3e-4f;
	public float beta1 = 0.9f;
	public float beta2 = 0.999f;
	public float adamEps = 1e-8f;

	/** Global gradient norm clipping, applied by the caller before step(). */
	public float gradClip = 0.5f;

	private int adamStep = 0;

	// scratch, all owned so the forward pass allocates nothing
	private final Tensor convOut;
	private final Tensor concat;
	private final Tensor hidden;
	private final Tensor memoryOut;
	private final Tensor actionLogits;
	private final Tensor slotLogits;
	private final Tensor targetLogits;
	private final Tensor value;

	private final Tensor dAction;
	private final Tensor dSlot;
	private final Tensor dTarget;
	private final Tensor dValueOut;
	private final Tensor dCell;
	private final Tensor dHidden;
	private final Tensor dHeadScratch;
	private final Tensor dPrev;
	private final Tensor dLstmIn;
	private final Tensor dConv;
	private final float[] dGrid;

	/** Retained so backward can walk the same im2col layout forward used. */
	private final float[] gridSnapshot;

	public Network( EnvConfig config, Random rng ){
		this.config = config;

		this.extraInputs = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT + HeroEncoder.FEATURES;

		//4x4 stride 2 halves a 48x48 grid, keeping the flattened size small enough that the
		//trunk stays cheap while still resolving a one-tile doorway
		this.conv = new Conv2D( config.spatialChannels(), config.gridWidth, 24, 4, 2, rng );

		int convFeatures = conv.outputSize();

		this.trunk = new Dense( convFeatures + extraInputs, 256, rng, 1f );
		this.memory = new LSTM( 256, 128, rng );

		this.actionHead = new Dense( 128, Action.size(), rng, 1f );
		this.slotHead = new Dense( 128, config.maxSlots, rng, 1f );
		this.targetHead = new Dense( 128, ActionMapper.TARGET_COUNT, rng, 1f );
		this.valueHead = new Dense( 128, 1, rng, 1f );

		this.convOut = new Tensor( 1, convFeatures );
		this.concat = new Tensor( 1, convFeatures + extraInputs );
		this.hidden = new Tensor( 1, 256 );
		this.memoryOut = new Tensor( 1, memory.size );
		this.actionLogits = new Tensor( 1, actionHead.out );
		this.slotLogits = new Tensor( 1, slotHead.out );
		this.targetLogits = new Tensor( 1, targetHead.out );
		this.value = new Tensor( 1, 1 );

		this.dAction = new Tensor( 1, actionHead.out );
		this.dSlot = new Tensor( 1, slotHead.out );
		this.dTarget = new Tensor( 1, targetHead.out );
		this.dValueOut = new Tensor( 1, 1 );
		this.dCell = new Tensor( 1, memory.size );
		this.dHidden = new Tensor( 1, memory.size );
		this.dHeadScratch = new Tensor( 1, memory.size );
		this.dPrev = new Tensor( 1, memory.size );
		this.dLstmIn = new Tensor( 1, 256 );
		this.dConv = new Tensor( 1, trunk.in );

		this.dGrid = new float[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
		this.gridSnapshot = new float[ dGrid.length ];
	}

	public int actionCount(){ return actionHead.out; }
	public int slotCount(){ return slotHead.out; }
	public int targetCount(){ return targetHead.out; }

	public void resetState(){
		memory.reset();
	}

	/** Serialises the recurrent state, so a trajectory can be split without losing context. */
	public float[] state(){
		return memory.state().data.clone();
	}

	public void restoreState( float[] s ){
		if (s == null || s.length != memory.size) return;
		System.arraycopy( s, 0, memory.state().data, 0, s.length );
	}

	/** Forward pass. Logits and value stay valid until the next call. */
	public void forward( float[] grid, float[] inventory, float[] heroFeatures ){
		System.arraycopy( grid, 0, gridSnapshot, 0,
				Math.min( grid.length, gridSnapshot.length ) );

		conv.forward( grid, convOut );

		System.arraycopy( convOut.data, 0, concat.data, 0, convOut.cols );
		int off = convOut.cols;
		System.arraycopy( inventory, 0, concat.data, off, inventory.length );
		off += config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		System.arraycopy( heroFeatures, 0, concat.data, off, heroFeatures.length );

trunk.forward( concat, hidden );

		//the LSTM narrows 256 to 128, so its output goes in its own buffer. Writing it back into
		//'hidden' would leave the tail of the trunk output in place and the heads would then be
		//fed the wrong width.
		memory.forward( hidden, memoryOut );

		actionHead.forward( memoryOut, actionLogits );
		slotHead.forward( memoryOut, slotLogits );
		targetHead.forward( memoryOut, targetLogits );
		valueHead.forward( memoryOut, value );
	}

	public float[] actionLogits(){ return actionLogits.data; }
	public float[] slotLogits(){ return slotLogits.data; }
	public float[] targetLogits(){ return targetLogits.data; }
	public float value(){ return value.data[ 0 ]; }

	// --------------------------------------------------------------------------- backward

	/**
	 * Backpropagates one timestep.
	 *
	 * The caller supplies gradients with respect to each head's output, which keeps the PPO loss
	 * outside the network where it belongs and lets the same backward serve PPO and IMPALA.
	 *
	 * @param dActionLogits dL/d(action logits), or null for zero
	 * @param dSlotLogits   dL/d(slot logits), or null
	 * @param dTargetLogits dL/d(target logits), or null
	 * @param dCritic       dL/d(critic output)
	 * @param dPrevState    gradient into the previous recurrent state, accumulated into
	 */
	public void backward( float[] dActionLogits, float[] dSlotLogits, float[] dTargetLogits,
			float dCritic, float[] dPrevState ){

		//dHidden holds d/d(lstm output) and is 128 wide
		Tensor dHiddenT = new Tensor( 1, memoryOut.cols );

		if (dActionLogits != null) copyInto( dActionLogits, dAction );
		else dAction.fill( 0f );
		if (dSlotLogits != null) copyInto( dSlotLogits, dSlot );
		else dSlot.fill( 0f );
		if (dTargetLogits != null) copyInto( dTargetLogits, dTarget );
		else dTarget.fill( 0f );

		dValueOut.data[ 0 ] = dCritic;

		//accumulate each head's gradient back into the LSTM output
		dHidden.fill( 0f );
		headGradient( actionHead, dAction );
		headGradient( slotHead, dSlot );
		headGradient( targetHead, dTarget );
		headGradient( valueHead, dValueOut );

		//copy only now, once the heads have finished accumulating into dHidden
		copyInto( dHidden.data, dHiddenT );

		dPrev.fill( 0f );
		if (dPrevState != null){
			System.arraycopy( dPrevState, 0, dPrev.data, 0,
					Math.min( dPrevState.length, dPrev.cols ) );
		}

		memory.backward( hidden, dHiddenT, dPrev, dCell );


		dLstmIn.fill( 0f );
		System.arraycopy( memory.inputGrad(), 0, dLstmIn.data, 0,
				Math.min( memory.inputGradLen(), dLstmIn.cols ) );

		dConv.fill( 0f );
		trunk.backward( concat, dLstmIn, dConv );

		java.util.Arrays.fill( dGrid, 0f );
		conv.backward( gridSnapshot, dConv, dGrid );
	}

	/** dL/d(prev recurrent state) accumulated by the last backward. */
	public float[] prevStateGradient(){
		return dPrev.data;
	}

	/** dL/d(grid), accumulated by the last backward. */
	public float[] gridGradient(){
		return dGrid;
	}

	private static void copyInto( float[] src, Tensor into ){
		into.fill( 0f );
		System.arraycopy( src, 0, into.data, 0, Math.min( src.length, into.cols ) );
	}

	/** Accumulates one head's gradient into dHidden, without allocating. */
	private void headGradient( Dense head, Tensor dOut ){
		head.backward( memoryOut, dOut, dHeadScratch );
		dHidden.addScaled( dHeadScratch, 1f );
	}

	// --------------------------------------------------------------------------- optimiser

	public void zeroGrad(){
		conv.zeroGrad();
		trunk.zeroGrad();
		memory.zeroGrad();
		actionHead.zeroGrad();
		slotHead.zeroGrad();
		targetHead.zeroGrad();
		valueHead.zeroGrad();
	}

	/**
	 * Applies one optimiser step.
	 *
	 * @param gradScale normalisation the caller applied, eg. 1/minibatchSize
	 */
	public void step( float gradScale ){
		adamStep++;
		float s = gradScale;
		conv.adamStep( learningRate, beta1, beta2, adamEps, s, adamStep );
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

/** Scalar parameter count, for diagnostics. */
	public long parameterCount(){
		return (long) conv.W.size() + conv.b.size()
				+ trunk.W.size() + trunk.b.size()
				+ memory.W.size() + memory.b.size()
				+ actionHead.W.size() + actionHead.b.size()
				+ slotHead.W.size() + slotHead.b.size()
				+ targetHead.W.size() + targetHead.b.size()
				+ valueHead.W.size() + valueHead.b.size();
	}

	// --------------------------------------------------------------------------- checkpoints

	/** One layer's weights, as a flat pair of tensors plus its shape. */
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

	/**
	 * Every trainable tensor, in a fixed order.
	 *
	 * Exposed as data rather than as live references so the checkpoint format is defined by this
	 * one method. A trainer can then serialise a policy without knowing anything about the layers
	 * that make it up, and a checkpoint written by an older build fails loudly on a shape mismatch
	 * instead of silently loading into the wrong parameters.
	 */
	public Layer[] layers(){
		return new Layer[] {
				new Layer( "conv.W", conv.W.rows, conv.W.cols, conv.W.data, conv.b.data ),
				new Layer( "trunk.W", trunk.W.rows, trunk.W.cols, trunk.W.data, trunk.b.data ),
				new Layer( "memory.W", memory.W.rows, memory.W.cols, memory.W.data, memory.b.data ),
				new Layer( "actionHead.W", actionHead.W.rows, actionHead.W.cols, actionHead.W.data, actionHead.b.data ),
				new Layer( "slotHead.W", slotHead.W.rows, slotHead.W.cols, slotHead.W.data, slotHead.b.data ),
				new Layer( "targetHead.W", targetHead.W.rows, targetHead.W.cols, targetHead.W.data, targetHead.b.data ),
				new Layer( "valueHead.W", valueHead.W.rows, valueHead.W.cols, valueHead.W.data, valueHead.b.data ),
		};
	}

	/** Copies weights from a checkpoint entry, or reports why it does not fit. */
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

	/** Copies every parameter out of this network into {@code target}. */
	public void copyParametersTo( Network target ){
		Layer[] src = layers();
		Layer[] dst = target.layers();
		for (int i = 0; i < src.length; i++){
			System.arraycopy( src[ i ].weights, 0, dst[ i ].weights, 0, src[ i ].weights.length );
			System.arraycopy( src[ i ].bias, 0, dst[ i ].bias, 0, src[ i ].bias.length );
		}
	}
}
