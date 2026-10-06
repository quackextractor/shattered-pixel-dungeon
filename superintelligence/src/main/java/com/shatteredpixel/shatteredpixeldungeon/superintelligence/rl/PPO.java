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

	public final Random rng;

	private final ArrayList<Transition> buffer = new ArrayList<>();
	private final ArrayList<Integer> episodeEnds = new ArrayList<>();

	private final float[] scratchProbs;
	private final float[] scratchGrid;
	private final float[] scratchGrad;

	private final int gridSize;
	private final int inventorySize;
	private final int heroSize;

	// diagnostics from the last update
	public float lastPolicyLoss;
	public float lastValueLoss;
	public float lastEntropy;
	public float lastKLDivergence;
	public float lastClipFraction;
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

		this.scratchProbs = new float[ Math.max( Action.size(), config.maxSlots ) ];
		this.scratchGrid = new float[ gridSize ];
		this.scratchGrad = new float[ Action.size() ];
	}

	// --------------------------------------------------------------------------- rollout

	/**
	 * Runs one episode and records every decision.
	 *
	 * @return the transitions recorded, which are also appended to this agent's update buffer
	 */
	public ArrayList<Transition> rollout( SPDEnv env, String seed, HeroClass heroClass ){
		env.reset( seed, heroClass );
		network.resetState();

		ArrayList<Transition> episode = new ArrayList<>();

		while (env.running()){
			network.forward( env.grid(), env.inventory(), env.heroFeatures() );

			int liveHead = headFor( env.mode() );

			int actionIndex = Policy.sample( network.actionLogits(), env.actionMask(), rng );

			int slotIndex = Policy.sample( network.slotLogits(), env.slotMask(), rng );

			int targetIndex = Policy.sample( network.targetLogits(), env.targetMask(), rng );

			//the head that is actually live supplies the stored behaviour log-probability
			float oldLogProbability;
					switch (liveHead) {
				case Policy.HEAD_SLOT: {
					Policy.probabilities( network.slotLogits(), env.slotMask(), scratchProbs );
											oldLogProbability = Policy.logProbability( scratchProbs, slotIndex );
					break;
				}
				case Policy.HEAD_TARGET: {
					Policy.probabilities( network.targetLogits(), env.targetMask(), scratchProbs );
											oldLogProbability = Policy.logProbability( scratchProbs, targetIndex );
					break;
				}
				default: {
					Policy.probabilities( network.actionLogits(), env.actionMask(), scratchProbs );
											oldLogProbability = Policy.logProbability( scratchProbs, actionIndex );
					break;
				}
			}

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

			episode.add( t );
			buffer.add( t );

			if (!env.running()) break;
		}

		network.resetState();
		episodeEnds.add( buffer.size() );
		return episode;
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
		lastMinibatches = 0;

		normaliseAdvantages();

		int n = buffer.size();

		for (int epoch = 0; epoch < epochs; epoch++){
			shuffle( buffer );
			network.resetState();

			for (int start = 0; start < n; start += minibatchSize){
				int end = Math.min( n, start + minibatchSize );
				processMinibatch( start, end );
				network.step( 1f / (end - start) );
				lastMinibatches++;
			}
		}

		network.learningRate = learningRate;
		network.resetState();
		clearBuffer();
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
			int chosen;

			switch (t.liveHead) {
				case Policy.HEAD_SLOT:
					logits = network.slotLogits();
					mask = t.slotMask;
					chosen = t.slotIndex;
					probabilities = scratchProbs;
					break;
				case Policy.HEAD_TARGET:
					logits = network.targetLogits();
					mask = t.targetMask;
					chosen = t.slotIndex;
					probabilities = scratchProbs;
					break;
				default:
					logits = network.actionLogits();
					mask = t.actionMask;
					chosen = t.actionIndex;
					probabilities = scratchProbs;
					break;
			}

			java.util.Arrays.fill( scratchGrad, 0f );
			if (probabilities.length < logits.length){
				probabilities = new float[ logits.length ];
			}

			Policy.probabilities( logits, mask, probabilities );
			Policy.accumulatePolicyGradient( probabilities, mask, chosen,
					t.oldLogProbability, t.advantage, clipEpsilon, entropyCoeff, scratchGrad );

			float dValue = Policy.valueGradient( network.value(), t.returnValue );

			network.backward( t.liveHead == Policy.HEAD_ACTION ? scratchGrad : null,
					t.liveHead == Policy.HEAD_SLOT ? scratchGrad : null,
					t.liveHead == Policy.HEAD_TARGET ? scratchGrad : null,
					dValue, null );

			policyLoss -= t.advantage;
			valueLoss += Policy.valueLoss( network.value(), t.returnValue );
			entropy += Policy.entropy( probabilities );
			kl += approximateKL( t, logits, mask, probabilities );
			if (Math.abs( t.advantage ) > clipEpsilon ) clipped++;
		}

		int count = Math.max( 1, to - from );
		lastPolicyLoss += policyLoss / count;
		lastValueLoss += valueLoss / count;
		lastEntropy += entropy / count;
		lastKLDivergence += kl / count;
		lastClipFraction += clipped / count;
	}

	/** KL between the behaviour distribution and the current one, recomputed from the ratio. */
	private float approximateKL( Transition t, float[] logits, float[] mask, float[] probabilities ){
		float newLog = Policy.logProbability( probabilities, indexFor( t, mask, logits ) );
		float ratio = (float) Math.exp( newLog - t.oldLogProbability );
		float r = ratio;
		//KL for small deviations is well approximated by (r-1) - ln r
		return (r - 1f) - (float) Math.log( Math.max( r, 1e-8f ) );
	}

	private static int indexFor( Transition t, float[] mask, float[] logits ){
		return (t.liveHead == Policy.HEAD_ACTION) ? t.actionIndex : t.slotIndex;
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