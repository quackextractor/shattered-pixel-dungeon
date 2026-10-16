package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

/**
 * One timestep's forward pass, kept so its backward pass can run after later timesteps.
 *
 * Every layer caches what its own backward needs - the pre-activation for the activation derivative,
 * and the LSTM additionally the carried states and the concatenated input - in fields that a second
 * forward pass overwrites. Backpropagating a contiguous chunk therefore means holding one of these per
 * step and restoring it before each step's backward, in reverse order.
 *
 * Without it the recurrent gradient is truncated at length one: the update backpropagates each sample
 * in isolation with {@code dPrev} and {@code dCell} cleared, so {@code LSTM.W_hh} never receives a
 * gradient for retaining anything across steps - which for a partially observable grid game is the
 * whole point of the recurrent layer.
 *
 * Sized from the network's own shapes rather than restated, because a restated width would read a
 * plausible neighbouring array instead of failing.
 */
public class StepCache {

	final float[] grid;
	final float[] concat;
	final float[] hidden;

	final float[] trunkPreAct;
	final float[] actionPreAct;
	final float[] slotPreAct;
	final float[] targetPreAct;
	final float[] valuePreAct;

	final float[] memoryOut;
	final float[] lstm;
	final int lstmStride;

	StepCache( Network net ){
		this.grid = new float[ net.gridLength() ];
		this.concat = new float[ net.concatLength() ];
		this.hidden = new float[ net.trunkOut() ];
		this.trunkPreAct = new float[ net.trunkOut() ];
		this.actionPreAct = new float[ net.actionCount() ];
		this.slotPreAct = new float[ net.slotCount() ];
		this.targetPreAct = new float[ net.targetCount() ];
		this.valuePreAct = new float[ 1 ];
		this.memoryOut = new float[ net.memoryOut() ];
		this.lstmStride = net.lstmCacheLength();
		this.lstm = new float[ net.lstmCacheLength() ];
	}
}