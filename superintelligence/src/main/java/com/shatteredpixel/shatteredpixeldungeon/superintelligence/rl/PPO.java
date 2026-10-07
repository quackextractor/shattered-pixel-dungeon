package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The PPO learner: holds a buffer of transitions with advantages already computed, and updates from
 * it.
 *
 * research.md: "The Core Algorithm: Proximal Policy Optimization (PPO)... PPO can effectively pool
 * the gradients from all these simultaneous runs to make steady, reliable updates to the policy
 * without catastrophic forgetting."
 *
 * Pooling is what the parallel architecture is for. Every worker's contribution goes into one buffer
 * and one update consumes all of it, so a single gradient step sees every worker's experience at
 * once. That is what keeps updates steady as worker count rises rather than getting noisier with each
 * extra worker.
 *
 * **This class does not collect.** It used to: `collect()` drove the env, the network and the masks,
 * and `update()` computed the advantages over whatever it had. Both halves moved. Collection is
 * {@link EpisodeCollector}, in a worker process, because the advantage recursion needs consecutive
 * steps and a worker holds its whole episode while a trainer never would - see
 * {@code PLAN-data-flow.md}. Keeping one copy of the step loop is deliberate: two would drift.
 *
 * One honest approximation, stated rather than buried: the update replays each stored observation
 * through the current weights and backpropagates each step in isolation, so the recurrent gradient
 * is truncated at length one. An exact through-time gradient would need every timestep's
 * pre-activation state retained until the update, which at this sequence length is gigabytes. The
 * trunk, the heads and the critic all get exact gradients; the LSTM gets exact gradients within a
 * step and none across steps.
 */
public class PPO {

	public final EnvConfig config;
	public final Network network;

	// hyperparameters, exposed so the trainer can anneal them
	public float gamma = 0.99f;
	public float lambda = 0.95f;
	public float clipEpsilon = 0.2f;
	public float entropyCoeff = 0.01f;
	public float learningRate = 3e-4f;

	public int minibatchSize = 32;
	public int epochs = 4;

	/**
	 * Transitions between updates, with their advantages already computed.
	 *
	 * This class no longer collects anything. A worker collects with {@link EpisodeCollector}, where
	 * the backward advantage pass runs over the whole episode rather than over a chunk of it - see
	 * {@code PLAN-data-flow.md} step 2 - and the trainer adds what its workers sent through
	 * {@link #addAll}. What is left here is the learner.
	 *
	 * What bounds this buffer is the trainer's memory rather than a worker's, so it is the trainer
	 * that caps it, at `--max-samples-per-generation`.
	 */
	public final Random rng;

	private final ArrayList<Transition> buffer = new ArrayList<>();

	//one probability buffer per head. The three heads have different widths and the loss reads
	//the probability vector against that head's own mask, so a shared buffer sized for the widest
	//head would run off the end of the narrower masks.
	private final float[] actionProbs;
	private final float[] slotProbs;
	private final float[] targetProbs;

	private final float[] scratchGrid;
	private final float[] scratchGradAction;
	private final float[] scratchGradSlot;
	private final float[] scratchGradTarget;

	private final int gridSize;
	private final int inventorySize;
	private final int heroSize;

	/**
	 * Adds transitions whose advantages were computed elsewhere.
	 *
	 * Used by the trainer, whose advantages come from the workers. A worker's backward pass runs over
	 * its whole episode, so the trainer must not compute them again: it only holds a sample of the
	 * steps, and GAE over a non-adjacent subset is the exact failure this design exists to avoid.
	 * Normalisation is still done here, because that is a property of the batch rather than of any
	 * one episode.
	 *
	 * The transitions are stored by reference and dropped by {@link #clearBuffer}. They come from the
	 * transition codec, which allocates rather than pools, so there is nothing to give back.
	 */
	public void addAll( List<Transition> transitions ){
		buffer.addAll( transitions );
	}

	/**
	 * Discards the oldest {@code count} transitions.
	 *
	 * Used to hold a generation inside its memory cap. The oldest go rather than a random subset
	 * because the recent end of a generation is where the terminal and truncated episodes are, and
	 * termination is a signal the critic cannot do without.
	 */
	public void dropOldest( int count ){
		buffer.subList( 0, Math.min( count, buffer.size() ) ).clear();
	}

	// diagnostics from the last update, each a mean over the samples seen rather than a running sum
	public float lastPolicyLoss;
	public float lastValueLoss;
	public float lastEntropy;
public float lastKLDivergence;
// fraction of samples whose PPO ratio fell outside [1-eps, 1+eps], so the surrogate's min() clipped them
public float lastClipFraction;
	public float lastGradNorm;
	public float lastGradClipped;
	public int lastMinibatches;

	public PPO( EnvConfig config, Random rng ){
		this.config = config;
		this.rng = rng;
		this.network = new Network( config, rng );
		this.network.learningRate = learningRate;

		this.gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		this.inventorySize = config.maxSlots
				* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT;
		this.heroSize = com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES;

		this.actionProbs = new float[ Action.size() ];
		this.slotProbs = new float[ config.maxSlots ];
		this.targetProbs = new float[
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT ];

		this.scratchGrid = new float[ gridSize ];
		this.scratchGradAction = new float[ Action.size() ];
		this.scratchGradSlot = new float[ config.maxSlots ];
		this.scratchGradTarget = new float[
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT ];
	}


	// --------------------------------------------------------------------------- update

	public int bufferSize(){
		return buffer.size();
	}

	public ArrayList<Transition> buffer(){
		return buffer;
	}

	/**
	 * Empties the buffer.
	 *
	 * Deliberately does not return the transitions to the pool. The ones here came from the transition
	 * codec, which allocates rather than pools, so there is nothing to give back - and recycling
	 * objects across generations is exactly what would let a decode bug read the previous generation's
	 * numbers instead of failing. The worker side is where pooling belongs, and it is a small bounded
	 * buffer there.
	 */
	public void clearBuffer(){
		buffer.clear();
	}

	/**
	 * Applies one PPO update over everything in the buffer.
	 *
	 * Each sample is replayed through the current weights, so the epochs genuinely re-evaluate the
	 * same observation against a policy that has already moved, which is what the clipping ratio
	 * is supposed to measure.
	 *
	 * The advantages are used as they arrived. They were computed over each worker's whole episode,
	 * and the buffer here holds a sample of those episodes' steps - so recomputing them over what
	 * arrived would be exactly the non-adjacent recursion this design exists to avoid. That leaves
	 * normalisation as the only pass that touches them, which is a property of the batch rather than
	 * of any one episode and is therefore still correct here.
	 */
	public void update(){
		if (buffer.isEmpty()) return;

		network.zeroGrad();

		lastPolicyLoss = 0;
		lastValueLoss = 0;
		lastEntropy = 0;
		lastKLDivergence = 0;
		lastClipFraction = 0;
		lastGradNorm = 0;
		lastGradClipped = 0;
		lastMinibatches = 0;

		normaliseAdvantages();

		int n = buffer.size();

		for (int epoch = 0; epoch < epochs; epoch++){
			shuffle( buffer );
			network.resetState();

			for (int start = 0; start < n; start += minibatchSize){
				int end = Math.min( n, start + minibatchSize );
				processMinibatch( start, end );
				network.step( 1f );
				lastMinibatches++;
			}
		}

		//the per-minibatch figures were each averaged over their own samples and then summed, so the
		//total depended on how many minibatches and epochs ran. That made the printed loss grow just
		//from a longer update. Divide it back out: the report is a mean over the samples seen.
		if (lastMinibatches > 0){
			lastPolicyLoss /= lastMinibatches;
			lastValueLoss /= lastMinibatches;
			lastEntropy /= lastMinibatches;
			lastKLDivergence /= lastMinibatches;
			lastClipFraction /= lastMinibatches;
			lastGradNorm /= lastMinibatches;
			lastGradClipped /= lastMinibatches;
		}

		network.learningRate = learningRate;
		network.resetState();
		clearBuffer();
	}

	/**
	 * Mean of the raw advantages currently in the buffer.
	 *
	 * Read before normalisation, because that is the diagnostic. A batch whose advantages are all
	 * equal has no gradient direction to offer, and once normalised it would present as a textbook mean
	 * of zero and standard deviation of one.
	 */
	public double meanAdvantage(){
		if (buffer.isEmpty()) return 0;
		double sum = 0;
		for (Transition t : buffer) sum += t.advantage;
		return sum / buffer.size();
	}

	/** Standard deviation of the raw advantages currently in the buffer. */
	public double advantageStdDev(){
		if (buffer.size() < 2) return 0;

		double mean = meanAdvantage();
		double sq = 0;
		for (Transition t : buffer){
			double d = t.advantage - mean;
			sq += d * d;
		}
		return Math.sqrt( sq / buffer.size() );
	}

	/** Zero-mean unit-variance advantages over the whole buffer, the usual PPO preconditioner. */
	private void normaliseAdvantages(){
		int n = buffer.size();
		if (n < 2) return;

		double sum = 0;
		for (Transition t : buffer) sum += t.advantage;
		double mean = sum / n;

		double sq = 0;
		for (Transition t : buffer){
			double d = t.advantage - mean;
			sq += d * d;
		}
		double std = Math.sqrt( sq / n );
		if (std < 1e-6) return;

		for (Transition t : buffer){
			t.advantage = (float) ((t.advantage - mean) / std);
		}
	}

	private void processMinibatch( int from, int to ){
		float policyLoss = 0;
		float valueLoss = 0;
		float entropy = 0;
		float kl = 0;
		float clipped = 0;

		network.zeroGrad();

		for (int i = from; i < to; i++){
			Transition t = buffer.get( i );

			t.unpackGrid( scratchGrid );
			network.forward( scratchGrid, t.inventory, t.hero );

			float[] probabilities;
			float[] mask;
			float[] logits;
			float[] gradient;
			int chosen;

			switch (t.liveHead) {
				case Policy.HEAD_SLOT:
					logits = network.slotLogits();
					mask = t.slotMask;
					chosen = t.slotIndex;
					probabilities = slotProbs;
					gradient = scratchGradSlot;
					break;
				case Policy.HEAD_TARGET:
					logits = network.targetLogits();
					mask = t.targetMask;
					chosen = t.slotIndex;
					probabilities = targetProbs;
					gradient = scratchGradTarget;
					break;
				default:
					logits = network.actionLogits();
					mask = t.actionMask;
					chosen = t.actionIndex;
					probabilities = actionProbs;
					gradient = scratchGradAction;
					break;
			}

			java.util.Arrays.fill( gradient, 0f );

			Policy.probabilities( logits, mask, probabilities );
			//whether the surrogate's clip bound this sample, which is PPO's clip fraction. Counting
			//|advantage| instead - which is what this did - measures nothing: after normalisation
			//advantages have unit variance, so that count is a constant near P(|N(0,1)| > 0.2).
			boolean clipBinding = Policy.accumulatePolicyGradient( probabilities, mask, chosen,
					t.oldLogProbability, t.advantage, clipEpsilon, entropyCoeff, gradient );

			float dValue = Policy.valueGradient( network.value(), t.returnValue );

			network.backward( t.liveHead == Policy.HEAD_ACTION ? gradient : null,
					t.liveHead == Policy.HEAD_SLOT ? gradient : null,
					t.liveHead == Policy.HEAD_TARGET ? gradient : null,
					dValue, null );

			policyLoss -= t.advantage;
			valueLoss += Policy.valueLoss( network.value(), t.returnValue );
			entropy += Policy.entropy( probabilities );
			kl += approximateKL( t, oldLogProbabilityFor( t, probabilities ), mask, probabilities );
			if (clipBinding) clipped++;
		}

		int count = Math.max( 1, to - from );
		lastPolicyLoss += policyLoss / count;
		lastValueLoss += valueLoss / count;
		lastEntropy += entropy / count;
		lastKLDivergence += kl / count;
		lastClipFraction += clipped / count;

		//average the minibatch, then clip, then step. Clipping before averaging would make the ceiling
		//mean something that changes with minibatchSize, and Network.gradClip is declared as an
		//absolute norm for a reason. Averaging here rather than through step's gradScale also means
		//the norm reported below is the norm of the gradient that was actually applied.
		network.scaleGradients( 1f / count );
		float norm = network.clipGradients( network.gradClip );
		lastGradNorm += norm;
		if (norm > network.gradClip) lastGradClipped++;
	}

	/** KL between the behaviour distribution and the current one, recomputed from the ratio. */
	private float approximateKL( Transition t, float behaviourLogProbability, float[] mask, float[] probabilities ){
		int chosen = (t.liveHead == Policy.HEAD_ACTION) ? t.actionIndex : t.slotIndex;

		float newLog = Policy.logProbability( probabilities, chosen );
		float ratio = (float) Math.exp( newLog - behaviourLogProbability );

		//KL for small deviations is well approximated by (r-1) - ln r
		return (ratio - 1f) - (float) Math.log( Math.max( ratio, 1e-8f ) );
	}

	/** The behaviour log-probability recorded at rollout time. */
	private float oldLogProbabilityFor( Transition t, float[] probabilities ){
		return t.oldLogProbability;
	}

	private void shuffle( ArrayList<Transition> list ){
		for (int i = list.size() - 1; i > 0; i--){
			int j = rng.nextInt( i + 1 );
			Transition tmp = list.get( i );
			list.set( i, list.get( j ) );
			list.set( j, tmp );
		}
	}
}
