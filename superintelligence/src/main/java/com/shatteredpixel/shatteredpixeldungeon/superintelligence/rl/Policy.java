package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayList;
import java.util.Random;

/**
 * Masked categorical sampling and the PPO loss.
 *
 * The environment supplies a legal-action mask every step, so the softmax is restricted to legal
 * choices before normalising. Zeroing illegal actions *after* the softmax would leave the chosen
 * action's probability too low by exactly the mass that was discarded, which biases every ratio in
 * the update and produces a gradient that fights the mask instead of learning through it.
 */
public class Policy {

	/** Which head a step's decision came from. */
	public static final int HEAD_ACTION = 0;
	public static final int HEAD_SLOT   = 1;
	public static final int HEAD_TARGET = 2;

	private Policy() {}

	/**
	 * Samples an index from masked logits.
	 *
	 * @return the sampled index, or 0 if nothing is legal, so the caller fails an action rather
	 *         than crashing
	 */
	public static int sample( float[] logits, float[] mask, Random rng ){
		float max = Float.NEGATIVE_INFINITY;
		boolean any = false;

		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ){
				any = true;
				if (logits[ i ] > max ) max = logits[ i ];
			}
		}
		if (!any) return 0;

		double total = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ) total += Math.exp( logits[ i ] - max );
		}

		double target = rng.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] <= 0.5f ) continue;
			cumulative += Math.exp( logits[ i ] - max );
			if (cumulative >= target ) return i;
		}

		for (int i = logits.length - 1; i >= 0; i--){
			if (mask[ i ] > 0.5f ) return i;
		}
		return 0;
	}

	/** Softmax over the legal entries only, written into {@code out}. */
	public static void probabilities( float[] logits, float[] mask, float[] out ){
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f && logits[ i ] > max ) max = logits[ i ];
		}

		double total = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ) total += Math.exp( logits[ i ] - max );
		}

		java.util.Arrays.fill( out, 0f );
		if (total <= 0) return;

		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ){
				out[ i ] = (float) (Math.exp( logits[ i ] - max ) / total );
			}
		}
	}

	/** Entropy of the masked distribution, logged to show whether exploration is collapsing. */
	public static float entropy( float[] probs ){
		double h = 0;
		for (float p : probs){
			if (p > 1e-12f) h -= p * Math.log( p );
		}
		return (float) h;
	}

		/**
	 * Accumulates the PPO policy-gradient contribution.
	 *
	 * The clipped surrogate is L = min(r*A, clip(r, 1-eps, 1+eps)*A) with r = pi_new/pi_old. Where
	 * the clip is not binding, dL/dlogpi_new = A*r; where it is binding the gradient is zero,
	 * which is exactly what stops one outlier sample from moving the policy.
	 *
	 * @param probabilities      current policy's distribution over the legal set, for the live head
	 * @param chosenIndex        the index the behaviour policy sampled
	 * @param oldLogProbability  log pi_behaviour(chosen)
	 * @param advantage          GAE advantage
	 * @param outGradient        dL/dlogits for the live head, accumulated into
	 * @return                   true when the clip bound the ratio, i.e. the surrogate's min() selected
	 *                           the clipped branch and this sample contributed no policy gradient
	 */
	public static boolean accumulatePolicyGradient( float[] probabilities, float[] mask,
			int chosenIndex, float oldLogProbability, float advantage,
			float epsilon, float entropyCoeff, float[] outGradient ){

		float newProbability = Math.max( probabilities[ chosenIndex ], 1e-8f );
		float ratio = (float) Math.exp( (float) Math.log( newProbability ) - oldLogProbability );

		float low = 1f - epsilon;
		float high = 1f + epsilon;
		float clippedRatio = ratio < low ? low : (ratio > high ? high : ratio);
		boolean clipBinding = clippedRatio != ratio;

		float dLogProbability = clipBinding ? 0f : advantage * ratio;

		outGradient[ chosenIndex ] += dLogProbability * (1f - newProbability);

		if (entropyCoeff != 0f){
			float h = entropy( probabilities );
			for (int i = 0; i < probabilities.length; i++){
				if (mask[ i ] <= 0.5f ) continue;
				float p = probabilities[ i ];
				if (p <= 1e-8f) continue;

				outGradient[ i ] -= entropyCoeff * ( -p * ( (float) Math.log( p ) + h ) );
			}
		}

		return clipBinding;
	}

	/** log pi(chosen) under a masked distribution, recorded during the rollout. */
	public static float logProbability( float[] probabilities, int index ){
		return (float) Math.log( Math.max( probabilities[ index ], 1e-8f ) );
	}

	/** Residual for the squared-value loss; already the gradient wrt the critic output. */
	public static float valueGradient( float predicted, float target ){
		return predicted - target;
	}

	/**
	 * The same residual, bounded.
	 *
	 * <p>A terminal penalty of -3000 is a legitimate target and is one of the few samples in a batch that
	 * say anything about dying. Squared, its gradient is two orders of magnitude above every other
	 * sample's, and after the trunk is shared with the actor that is what global clipping then squashes
	 * everything else to fit. Clipping the residual keeps the sample's direction and caps its magnitude,
	 * which is the property the loss needs - a sample that is far from the target should pull harder, but
	 * not without limit.
	 */
	public static float valueGradient( float predicted, float target, float maxResidual ){
		float delta = predicted - target;
		if (maxResidual > 0f) return Math.max( -maxResidual, Math.min( maxResidual, delta ));
		return delta;
	}

	/** Squared TD error, logged so the critic's convergence is visible. */
	public static float valueLoss( float predicted, float target ){
		float delta = predicted - target;
		return 0.5f * delta * delta;
	}

	/**
	 * TD error, Huber when {@code huber} is set.
	 *
	 * <p>Quadratic near zero and linear beyond one unit of normalised error, which is what keeps a batch
	 * of returns spanning thousands from being dominated by whichever episode happened to end badly.
	 */
	public static float valueLoss( float predicted, float target, boolean huber ){
		float delta = predicted - target;
		if (!huber) return 0.5f * delta * delta;

		float magnitude = Math.abs( delta );
		return magnitude <= 1f ? 0.5f * delta * delta : magnitude - 0.5f;
	}

/**
	 * Generalised advantage estimation, walked in reverse over one episode.
	 *
	 * Returns are computed per episode rather than across the pooled buffer, so an episode cut by
	 * the turn cap still bootstraps from its own final value estimate. Without that bootstrap a
	 * truncated episode teaches the critic that its last floor was worth nothing, and early in
	 * training almost every episode is truncated.
	 */
	public static void computeReturnsAndAdvantages( ArrayList<Transition> buffer, int from, int to,
			float gamma, float lambda ){
		float lastGae = 0f;
		for (int i = to - 1; i >= from; i--){
			Transition t = buffer.get( i );
			float nextValue = (i == to - 1) ? t.nextValue : buffer.get( i + 1 ).value;
			float nonTerminal = t.terminal ? 0f : 1f;
			float delta = t.reward + gamma * nextValue * nonTerminal - t.value;
			lastGae = delta + gamma * lambda * nonTerminal * lastGae;
			t.advantage = lastGae;
			t.returnValue = t.advantage + t.value;
		}
	}

	/**
	 * The same recursion over an episode's scalars, with no Transition objects involved.
	 *
	 * This is the form a worker needs. A whole episode of scalars is about 21 bytes a step, so the
	 * backward pass can run at the end of an episode that holds every step's numbers and only a
	 * sample of their observations. It is a separate method rather than a branch inside the one
	 * above because the two read differently and the check exists to keep them in agreement -
	 * {@code diag.GaeCheck} runs both over the same fixture and fails if they ever diverge.
	 *
	 * <pre>
	 *   rewards[i]    reward received on step i
	 *   values[i]     critic estimate for the state step i was taken in
	 *   finalValue    value of the state the last step led to, for a truncated episode
	 *   terminal[i]   whether step i ended the episode by death or victory
	 *   advantages[i] written
	 *   returns[i]    written
	 * </pre>
	 *
	 * @param count how many steps the episode has
	 */
	public static void computeEpisodeAdvantages( float[] rewards, float[] values, float[] terminal,
			int count, float finalValue, float gamma, float lambda,
			float[] advantages, float[] returns ){

		float lastGae = 0f;
		for (int i = count - 1; i >= 0; i--){
			float nextValue = (i == count - 1) ? finalValue : values[ i + 1 ];
			float nonTerminal = terminal[ i ] != 0f ? 0f : 1f;
			float delta = rewards[ i ] + gamma * nextValue * nonTerminal - values[ i ];
			lastGae = delta + gamma * lambda * nonTerminal * lastGae;
			advantages[ i ] = lastGae;
			returns[ i ] = lastGae + values[ i ];
		}
	}
}
