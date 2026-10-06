package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;

import java.util.ArrayList;
import java.util.Random;

/**
 * The PPO agent: rolls out episodes, stores transitions, and applies clipped updates.
 *
 * research.md: "The Core Algorithm: Proximal Policy Optimization (PPO)... PPO can effectively pool
 * the gradients from all these simultaneous runs to make steady, reliable updates to the policy
 * without catastrophic forgetting."
 *
 * Pooling is what the parallel architecture is for. Each worker fills its own slice of the shared
 * buffer and every update consumes all of it, so one gradient step sees every worker's experience
 * at once. That is what keeps updates steady as worker count rises rather than getting noisier
 * with each extra worker.
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
	 * Transitions collected between updates.
	 *
	 * Two reasons this is bounded rather than "one whole episode". A stored step is dominated by its
	 * packed grid, so {@code turnLimitTotal} steps of a single episode is gigabytes - more than a
	 * worker's heap, which makes an uncapped rollout an out-of-memory crash rather than a slow run.
	 * And PPO wants frequent updates regardless: the importance ratio compares the collecting
	 * policy against the current one, so the longer the policy is left unchanged over a long
	 * collection the worse that estimate gets.
	 *
	 * Truncating a collection is not the same as truncating an episode. The env is left running and
	 * the next call resumes it, so an episode spans however many chunks it needs. The cost is that
	 * advantage estimates stop at a chunk boundary rather than reaching back to the start of the
	 * episode - the same truncation already accepted for the recurrent gradient.
	 */
	public int rolloutCap = 2048;

	public final Random rng;

	private final ArrayList<Transition> buffer = new ArrayList<>();
	private final ArrayList<Integer> episodeEnds = new ArrayList<>();

	/** True while an episode is part-collected, so the next call resumes instead of resetting. */
	private boolean episodeInProgress;

	/**
	 * Set by {@link #update}, cleared on the next collect.
	 *
	 * An update changes the weights the recurrent state was produced by, and the update itself
	 * resets the state to shuffle minibatches. Resuming an episode through that boundary with a
	 * half-stale hidden state is worse than starting it fresh, so a weight change is treated as an
	 * information boundary: the agent forgets. Without this, where updates happen to land would
	 * silently change an episode's actions, and the same seed would stop reproducing.
	 */
	private boolean recurrentStateStale;

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

	// diagnostics from the last update, each a mean over the samples seen rather than a running sum
	public float lastPolicyLoss;
	public float lastValueLoss;
	public float lastEntropy;
	public float lastKLDivergence;
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

	// --------------------------------------------------------------------------- rollout

	/**
	 * Collects up to {@link #rolloutCap} transitions, resuming the current episode if one is
	 * part-collected.
	 *
	 * The env is deliberately left mid-episode when the cap is reached. The next call continues
	 * from there with the recurrent state intact, so an episode longer than the cap is collected
	 * across several calls rather than being cut short.
	 *
	 * @return the transitions collected by this call, also appended to this agent's update buffer
	 */
	public ArrayList<Transition> collect( SPDEnv env, String seed, HeroClass heroClass ){
		if (!episodeInProgress){
			env.reset( seed, heroClass );
			network.resetState();
			episodeInProgress = true;
			recurrentStateStale = false;
		} else if (recurrentStateStale){
			//the weights moved under this episode, so its hidden state no longer describes them
			network.resetState();
			recurrentStateStale = false;
		}

		ArrayList<Transition> chunk = new ArrayList<>();
		int before = buffer.size();

		while (env.running() && chunk.size() < rolloutCap){
			network.forward( env.grid(), env.inventory(), env.heroFeatures() );

			int liveHead = headFor( env.mode() );

			int actionIndex = Policy.sample( network.actionLogits(), env.actionMask(), rng );
			int slotIndex = Policy.sample( network.slotLogits(), env.slotMask(), rng );
			int targetIndex = Policy.sample( network.targetLogits(), env.targetMask(), rng );

			//the head that is actually live supplies the stored behaviour log-probability. Each
			//head gets its own buffer because they have different widths and the loss reads the
			//probability vector against that head's own mask.
			float oldLogProbability;
			int chosen;
			switch (liveHead) {
				case Policy.HEAD_SLOT:
					Policy.probabilities( network.slotLogits(), env.slotMask(), slotProbs );
					chosen = slotIndex;
					break;
				case Policy.HEAD_TARGET:
					Policy.probabilities( network.targetLogits(), env.targetMask(), targetProbs );
					chosen = targetIndex;
					break;
				default:
					Policy.probabilities( network.actionLogits(), env.actionMask(), actionProbs );
					chosen = actionIndex;
					break;
			}
			oldLogProbability = Policy.logProbability( probabilitiesFor( liveHead ), chosen );

			Transition t = Transition.take( gridSize, inventorySize, heroSize,
					Action.size(), config.maxSlots,
					com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT );

			t.packGrid( env.grid() );
			System.arraycopy( env.inventory(), 0, t.inventory, 0, inventorySize );
			System.arraycopy( env.heroFeatures(), 0, t.hero, 0, heroSize );
			System.arraycopy( env.actionMask(), 0, t.actionMask, 0, t.actionMask.length );
			System.arraycopy( env.slotMask(), 0, t.slotMask, 0, t.slotMask.length );
			System.arraycopy( env.targetMask(), 0, t.targetMask, 0, t.targetMask.length );

			t.liveHead = liveHead;
			t.actionIndex = actionIndex;
			t.slotIndex = slotIndex;
			t.oldLogProbability = oldLogProbability;
			t.value = network.value();

			Action action = Action.fromIndex( actionIndex );
			int secondary = (liveHead == Policy.HEAD_TARGET) ? targetIndex : slotIndex;

			t.reward = (float) env.step( action, secondary );

			//value of the state this step produced, used to bootstrap a truncated episode
			network.forward( env.grid(), env.inventory(), env.heroFeatures() );
			t.nextValue = network.value();
			t.terminal = env.endedNaturally();

			chunk.add( t );
			buffer.add( t );
		}

		if (!env.running()){
			//episode finished, so the next call starts a fresh one
			network.resetState();
			episodeInProgress = false;
		}

		//every collection is its own advantage segment, so GAE never reaches past the cap. A chunk
		//that happens to end an episode already has its bootstrap zeroed by the terminal flag.
		if (buffer.size() > before){
			episodeEnds.add( buffer.size() );
		}

		return chunk;
	}

	/**
	 * Runs whole episodes, recording every decision, until {@link #episodes} have finished.
	 *
	 * Drives {@link #collect} and ignores the cap, so it is for tests and single-process
	 * experiments. A long episode will still collect more than {@link #rolloutCap} in total.
	 *
	 * @return the transitions from the final episode only
	 */
	public ArrayList<Transition> rollout( SPDEnv env, String seed, HeroClass heroClass, int episodes ){
		ArrayList<Transition> last = new ArrayList<>();
		for (int i = 0; i < episodes; i++){
			last = new ArrayList<>();
			do {
				last = collect( env, seed, heroClass );
			} while ( episodeInProgress );
		}
		return last;
	}

	/** True while an episode is part-collected and the next collect() call will resume it. */
	public boolean episodeInProgress(){
		return episodeInProgress;
	}

	private float[] probabilitiesFor( int head ){
		switch (head) {
			case Policy.HEAD_SLOT:   return slotProbs;
			case Policy.HEAD_TARGET: return targetProbs;
			default:                return actionProbs;
		}
	}

	private static int headFor( EnvMode mode ){
		switch (mode) {
			case SLOT:
			case INVENTORY: return Policy.HEAD_SLOT;
			case TARGETING: return Policy.HEAD_TARGET;
			default:        return Policy.HEAD_ACTION;
		}
	}

	// --------------------------------------------------------------------------- update

	public int bufferSize(){
		return buffer.size();
	}

	public ArrayList<Transition> buffer(){
		return buffer;
	}

	/** Releases the buffer and its transitions back to the pool. */
	public void clearBuffer(){
		for (Transition t : buffer) t.release();
		buffer.clear();
		episodeEnds.clear();
	}

	/**
	 * Applies one PPO update over everything collected so far.
	 *
	 * Each sample is replayed through the current weights, so the epochs genuinely re-evaluate the
	 * same observation against a policy that has already moved, which is what the clipping ratio
	 * is supposed to measure.
	 */
	public void update(){
		if (buffer.isEmpty()) return;

		//GAE per episode, so a truncated episode bootstraps from its own last value
		int from = 0;
		for (int end : episodeEnds) {
			Policy.computeReturnsAndAdvantages( buffer, from, end, gamma, lambda );
			from = end;
		}

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

		//the weights have moved; any episode still in progress has to forget at this boundary
		if (episodeInProgress){
			recurrentStateStale = true;
		}
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
			Policy.accumulatePolicyGradient( probabilities, mask, chosen,
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
			if (Math.abs( t.advantage ) > clipEpsilon ) clipped++;
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