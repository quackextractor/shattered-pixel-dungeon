package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.List;

/**
 * A running estimate of the return's scale, so the critic is asked to fit O(1) rather than thousands.
 *
 * The reward function is priced for the agent, not for the optimiser: a stall costs 3000, a death 2500,
 * a descent pays 1800, so a return over a long episode lands somewhere in the thousands. The critic's
 * output is linear, so it can represent those numbers - but the trunk it shares with the actor does not,
 * and a squared error of 3000^2 produces a gradient two orders of magnitude larger than the policy's.
 * Global gradient clipping then squashes the actor's update towards zero, and the measured `valueLoss`
 * sat at 12,432 to 31,323 with no trend: the critic's own loss was dominating the shared representation.
 *
 * Normalising the target is the standard answer and the reason it works is that it does not change the
 * policy: the critic predicts a normalised value, and the advantage - which is what the actor consumes -
 * is already normalised batch-wide.
 *
 * Running rather than per-batch, because a single generation's returns depend on which episodes happened
 * to be in it. The first few generations are cold, so {@link #firstBatch} seeds the estimate from the
 * batch instead of from a prior, and {@link #ready} says when that has happened.
 */
public class ValueNormalizer {

	/** How much of each batch's answer replaces what is already believed. */
	public float momentum = 0.01f;

	/** Below this the estimate is treated as unknown and the first batch seeds it instead. */
	private static final float MIN_MOMENTUM = 1e-3f;

	private float mean;
	private float std = 1f;
	private boolean ready;

	public float mean(){ return mean; }
	public float std(){ return std; }

	/** Whether the estimate came from a real batch rather than the initial values. */
	public boolean ready(){ return ready; }

	/** Folds one generation's returns into the estimate. */
	public void update( List<Transition> batch ){
		if (batch.isEmpty()) return;

		double sum = 0;
		for (Transition t : batch) sum += t.returnValue;
		double batchMean = sum / batch.size();

		double sq = 0;
		for (Transition t : batch){
			double d = t.returnValue - batchMean;
			sq += d * d;
		}
		double batchStd = Math.max( 1e-4, Math.sqrt( sq / batch.size() ) );

		if (!ready || momentum < MIN_MOMENTUM){
			mean = (float) batchMean;
			std = (float) batchStd;
			ready = true;
			return;
		}

		mean = (1f - momentum) * mean + momentum * (float) batchMean;
		std = (1f - momentum) * std + momentum * (float) batchStd;
		if (std < 1e-3f) std = 1e-3f;
	}

	/** The normalised target for one raw return. */
	public float normalize( float returnValue ){
		return (returnValue - mean) / std;
	}

	/** Turns normalisation off, so the critic is asked for the raw return. */
	public void disable(){
		ready = false;
		mean = 0f;
		std = 1f;
	}
}