package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
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
	 * Threads for the update. Zero or one selects the serial path exactly.
	 *
	 * <p>One means the untouched original, not a one-threaded pool: the parameter copies and the
	 * gradient reduction would both be pure overhead, and the serial path is the reference the parallel
	 * one is checked against, so it has to stay exactly what it was.
	 */
	public int threads = 0;

	/**
	 * The threading and gradient-reduction half of an update.
	 *
	 * <p>Separate because it is a separate responsibility with a separate failure mode - it is the only
	 * place in the framework that touches threads the trainer does not own - and because the learner
	 * should be readable without first reading a thread pool. It drives {@link #oneSample} rather than
	 * a copy of it, so there is one implementation of the objective and not two.
	 */
	private final ShardedUpdate sharded = new ShardedUpdate( this );

	/** Threads the update will use, after capping against the machine. */
	public int threadCount(){
		return sharded.resolveThreads( threads );
	}

	/** Shuts the update pool down and releases the per-thread networks. Safe when there was none. */
	public void close(){
		sharded.close();
	}

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

		//resolves the thread count and creates the pool, which the sharded path assumes exists. Here
		//rather than in the constructor so that setting `threads` after construction is enough.
		threadCount();

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
			//Each sample now restores its own state, so the epoch boundary no longer has to clear it.
			//Left in as a guard: a transition with a null state - only synthetic ones - would otherwise
			//inherit whatever the last sample of the previous epoch left behind.
			network.resetState();

			for (int start = 0; start < n; start += minibatchSize){
				int end = Math.min( n, start + minibatchSize );
				oneMinibatch( start, end );
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
	 * One minibatch, whichever way it is going to be run, plus the shared bookkeeping over it.
	 *
	 * <p><b>One accumulation, not two.</b> The serial and sharded paths used to each carry their own copy
	 * of this arithmetic, which is the duplication {@link ShardedUpdate} exists to remove and which is
	 * how two paths come to report different numbers for the same gradient. The sharded path returns
	 * null when it declines a minibatch - fewer samples than threads, say - and the serial path runs it
	 * instead, so a run that asked for threads still produces the same result.
	 */
	private void oneMinibatch( int from, int to ){
		int count = Math.max( 1, to - from );

		//null means the sharded path declined - fewer samples than threads, say - and the serial path
		//runs it instead, so a run that asked for threads still produces the same result.
		Minibatch m = threads > 1 ? sharded.run( from, to ) : null;
		accumulate( m != null ? m : processMinibatch( from, to ), count );
	}

	private void accumulate( Minibatch m, int count ){
		lastPolicyLoss += m.policyLoss / count;
		lastValueLoss += m.valueLoss / count;
		lastEntropy += m.entropy / count;
		lastKLDivergence += m.kl / count;
		lastClipFraction += m.clipped / count;
		lastGradNorm += m.gradNorm;
		lastGradClipped += m.gradClipped;
	}

	/**
	 * One minibatch's contribution: the sums it produced and the gradient norm that was applied.
	 *
	 * <p>Gradients are scaled and clipped before this is returned, by whichever path ran, so the norm
	 * reported is the norm of the gradient that was actually applied rather than of one that was about
	 * to be.
	 */
	static class Minibatch {
		float policyLoss;
		float valueLoss;
		float entropy;
		float kl;
		float clipped;
		float gradNorm;
		int gradClipped;
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

/**
	 * One sample's forward, backward and diagnostics, on whichever network is passed in.
	 *
	 * <p>Factored out of {@link #processMinibatch} so the sharded path runs <em>this</em> method on a
	 * per-thread network rather than a copy of the loop. The alternative - duplicating the body and
	 * letting the two drift - is how a serial and a parallel implementation end up computing different
	 * objectives while both look correct.
	 *
	 * <p>Package-private rather than private because {@link ShardedUpdate} is the other half of the same
	 * update and has to call this. Making it public would invite a caller outside the package to build
	 * one of these per-thread networks, which is the one thing that has to stay in one place.
	 *
	 * <p>The per-thread network holds a copy of the parameters, so nothing here can observe or corrupt
	 * another's writes. Its gradients accumulate locally and are reduced afterwards.
	 *
	 * <p><b>The first three lines put {@code net} into the state {@code t} was decided under, and
	 * forwards it.</b> Restoring the state before the forward pass is what makes a stored observation
	 * replay to the value the behaviour policy saw. Without it the replay inherits whatever the
	 * previously processed sample left in {@code h}/{@code c} - and the buffer is shuffled, so that is
	 * an unrelated timestep of an unrelated episode. Measured by {@code replayprobe}: 0.000000 drift in
	 * collection order, up to 0.20 in shuffled order.
	 *
	 * <p>Two facts were wrong at once and they are the same fact seen from two sides. The objective
	 * was wrong, because the ratio {@code pi_new / pi_old} was formed across two different states -
	 * which is why the clip fraction sat at 0.84 on the first update, indistinguishable from
	 * {@code P(|N(0,1)| > 0.2)} and so reading as a plausible measurement. And the samples were
	 * <em>coupled</em>, sample <i>i</i> depending on sample <i>i-1</i>, which is why the parallel
	 * update was blocked: not by anything to do with threads, but by this.
	 *
	 * <p>A null {@code recurrentState} is the only legal way to lack one - synthetic transitions in the
	 * checks have no network to snapshot. A real transition always carries one, so a missing field is a
	 * codec bug rather than a supported case, and {@code statecheck} is what holds that line.
	 */
	Stats oneSample( Network net, Transition t, float[] grid,
			float[] slotProbs, float[] targetProbs, float[] actionProbs,
			float[] slotGrad, float[] targetGrad, float[] actionGrad ){

		Stats s = new Stats();

		//restore the state this step was decided under, before the forward reads it. the method comment below.
		if (t.recurrentState != null) net.loadState( t.recurrentState );

		t.unpackGrid( grid );
		net.forward( grid, t.inventory, t.hero );

		float[] probabilities;
		float[] mask;
		float[] logits;
		float[] gradient;
		int chosen;

		switch (t.liveHead) {
			case Policy.HEAD_SLOT:
				logits = net.slotLogits();
				mask = t.slotMask;
				chosen = t.slotIndex;
				probabilities = slotProbs;
				gradient = slotGrad;
				break;
			case Policy.HEAD_TARGET:
				logits = net.targetLogits();
				mask = t.targetMask;
				chosen = t.slotIndex;
				probabilities = targetProbs;
				gradient = targetGrad;
				break;
			default:
				logits = net.actionLogits();
				mask = t.actionMask;
				chosen = t.actionIndex;
				probabilities = actionProbs;
				gradient = actionGrad;
				break;
		}

		java.util.Arrays.fill( gradient, 0f );

		Policy.probabilities( logits, mask, probabilities );
		//whether the surrogate's clip bound this sample, which is PPO's clip fraction. Counting
		//|advantage| instead - which is what this did - measures nothing: after normalisation
		//advantages have unit variance, so that count is a constant near P(|N(0,1)| > 0.2).
		s.clipBinding = Policy.accumulatePolicyGradient( probabilities, mask, chosen,
				t.oldLogProbability, t.advantage, clipEpsilon, entropyCoeff, gradient );

		float dValue = Policy.valueGradient( net.value(), t.returnValue );

		net.backward( t.liveHead == Policy.HEAD_ACTION ? gradient : null,
				t.liveHead == Policy.HEAD_SLOT ? gradient : null,
				t.liveHead == Policy.HEAD_TARGET ? gradient : null,
				dValue, null );

		s.policyLoss = -t.advantage;
		s.valueLoss = Policy.valueLoss( net.value(), t.returnValue );
		s.entropy = Policy.entropy( probabilities );
		s.kl = approximateKL( t, t.oldLogProbability, mask, probabilities );
		return s;
	}

	/** One sample's contribution to the reported figures. */
	static class Stats {
		float policyLoss;
		float valueLoss;
		float entropy;
		float kl;
		boolean clipBinding;
	}

	/** The serial path: one minibatch on this thread. Returns what it produced. */
	private Minibatch processMinibatch( int from, int to ){
		Minibatch result = new Minibatch();
		float policyLoss = 0;
		float valueLoss = 0;
		float entropy = 0;
		float kl = 0;
		float clipped = 0;

		network.zeroGrad();

		for (int i = from; i < to; i++){
			Transition t = buffer.get( i );
			Stats s = oneSample( network, t, scratchGrid, slotProbs, targetProbs, actionProbs,
					scratchGradSlot, scratchGradTarget, scratchGradAction );

			policyLoss += s.policyLoss;
			valueLoss += s.valueLoss;
			entropy += s.entropy;
			kl += s.kl;
			clipped += s.clipBinding ? 1 : 0;
		}

		int count = Math.max( 1, to - from );

		//average the minibatch, then clip, then step. Clipping before averaging would make the ceiling
		//mean something that changes with minibatchSize, and Network.gradClip is declared as an
		//absolute norm for a reason. Averaging here rather than through step's gradScale also means
		//the norm reported is the norm of the gradient that was actually applied.
		network.scaleGradients( 1f / count );
		float norm = network.clipGradients( network.gradClip );

		result.policyLoss = policyLoss;
		result.valueLoss = valueLoss;
		result.entropy = entropy;
		result.kl = kl;
		result.clipped = clipped;
		result.gradNorm = norm;
		if (norm > network.gradClip) result.gradClipped = 1;

		return result;
	}

	/** KL between the behaviour distribution and the current one, recomputed from the ratio. */
	private float approximateKL( Transition t, float behaviourLogProbability, float[] mask, float[] probabilities ){
		int chosen = (t.liveHead == Policy.HEAD_ACTION) ? t.actionIndex : t.slotIndex;

		float newLog = Policy.logProbability( probabilities, chosen );
		float ratio = (float) Math.exp( newLog - behaviourLogProbability );

		//KL for small deviations is well approximated by (r-1) - ln r
		return (ratio - 1f) - (float) Math.log( Math.max( ratio, 1e-8f ) );
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

