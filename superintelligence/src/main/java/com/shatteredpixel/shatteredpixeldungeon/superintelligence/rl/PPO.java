package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class PPO {

	public final EnvConfig config;
	public final Network network;

	public float gamma = 0.99f;
	public float lambda = 0.95f;
	public float clipEpsilon = 0.2f;
	public float entropyCoeff = 0.01f;


	public float teacherWeight = 0f;

	public float demoWeight = 0f;

	public final List< Transition > demonstrations = new ArrayList<>();
	public float learningRate = 3e-4f;

	public int minibatchSize = 32;
	public int epochs = 4;


	public int chunkLength = 16;


	public final ValueNormalizer valueNorm = new ValueNormalizer();


	public boolean normaliseValues = true;


	public float valueGradientClip = 10f;


	public float valueLossCoefficient = 0.5f;


	public boolean huberValues = true;


	public int threads = 0;


	private final ShardedUpdate sharded = new ShardedUpdate();


	public int threadCount(){
		return sharded.resolveThreads( threads );
	}


	public List< Network > updateShardNetworks(){
		return sharded.shardNetworks();
	}


	public void close(){
		sharded.close();
	}


	public final Random rng;

	private final ArrayList<Transition> buffer = new ArrayList<>();

	private final float[] actionProbs;
	private final float[] slotProbs;
	private final float[] targetProbs;

	private final float[] scratchGrid;

	private StepCache[] chunkCaches;

	private final int gridSize;
	private final int inventorySize;
	private final int heroSize;


	public void addAll( List<Transition> transitions ){
		buffer.addAll( transitions );
	}


	public void dropOldest( int count ){
		buffer.subList( 0, Math.min( count, buffer.size() ) ).clear();
	}

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

		this.gridSize = config.spatialChannels() * config.gridWidth * config.gridHeight;
		this.inventorySize = config.maxSlots
				* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT;
		this.heroSize = com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES;

		this.actionProbs = new float[ Action.size() ];
		this.slotProbs = new float[ config.maxSlots ];
		this.targetProbs = new float[
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT ];

		this.scratchGrid = new float[ gridSize ];

		resizeCaches();
	}


	private void resizeCaches(){
		if (chunkCaches != null && chunkCaches.length == chunkLength) return;
		chunkCaches = new StepCache[ Math.max( 1, chunkLength ) ];
		for (int i = 0; i < chunkCaches.length; i++) chunkCaches[ i ] = network.takeCache();
	}

	public int bufferSize(){
		return buffer.size();
	}

	public ArrayList<Transition> buffer(){
		return buffer;
	}


	public void clearBuffer(){
		buffer.clear();
	}


	public void update(){
		if (buffer.isEmpty()) return;

		threadCount();
		resizeCaches();

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

		if (normaliseValues) valueNorm.update( buffer );

		buffer.addAll( demonstrations );
		demonstrations.clear();

		List< List< Transition > > chunks = chunks();

		for (int epoch = 0; epoch < epochs; epoch++){
			shuffleChunks( chunks );

			network.resetState();

			for (int start = 0; start < chunks.size(); start += minibatchSize){
				int end = Math.min( chunks.size(), start + minibatchSize );
				oneMinibatch( chunks, start, end );
				network.step( 1f );
				lastMinibatches++;
			}
		}

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


	public List< List< Transition > > chunks(){
		List< List< Transition > > out = new ArrayList<>( buffer.size() );

		List< Transition > current = new ArrayList<>();

		int episode = Integer.MIN_VALUE;
		int step = Integer.MIN_VALUE;

		for (Transition t : buffer){
			boolean adjacent = t.episodeId >= 0
					&& current.size() > 0
					&& t.episodeId == episode
					&& t.stepIndex == step + 1;

			if (!adjacent || current.size() >= chunkLength){
				if (!current.isEmpty()) out.add( current );
				current = new ArrayList<>();
			}

			current.add( t );
			episode = t.episodeId;
			step = t.stepIndex;
		}

		if (!current.isEmpty()) out.add( current );

		return out;
	}

	private void shuffleChunks( List< List< Transition > > chunks ){
		for (int i = chunks.size() - 1; i > 0; i--){
			int j = rng.nextInt( i + 1 );
			List< Transition > tmp = chunks.get( i );
			chunks.set( i, chunks.get( j ) );
			chunks.set( j, tmp );
		}
	}


	private void oneMinibatch( List< List< Transition > > chunks, int from, int to ){
		int count = Math.max( 1, to - from );

		Minibatch m = threads > 1 ? sharded.run( this, chunks, from, to ) : null;
		accumulate( m != null ? m : processMinibatch( chunks, from, to ), count );
	}

	private void accumulate( Minibatch m, int count ){
		int steps = Math.max( 1, m.steps );
		lastPolicyLoss += m.policyLoss / steps;
		lastValueLoss += m.valueLoss / steps;
		lastEntropy += m.entropy / steps;
		lastKLDivergence += m.kl / steps;
		lastClipFraction += m.clipped / count;
		lastGradNorm += m.gradNorm;
		lastGradClipped += m.gradClipped;
	}


	static class Minibatch {
		float policyLoss;
		float valueLoss;
		float entropy;
		float kl;
		float clipped;
		float gradNorm;
		int gradClipped;
		int steps;
	}


	public double meanAdvantage(){
		if (buffer.isEmpty()) return 0;
		double sum = 0;
		for (Transition t : buffer) sum += t.advantage;
		return sum / buffer.size();
	}


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


Stats oneSample( Network net, Transition t, float[] grid,
			float[] slotProbs, float[] targetProbs, float[] actionProbs ){

		StepCache[] caches = new StepCache[]{ net.takeCache() };

		return oneChunk( net, java.util.Collections.singletonList( t ), grid,
				slotProbs, targetProbs, actionProbs, caches, 1 );
	}

	Stats oneChunk( Network net, List< Transition > chunk, float[] grid,
			float[] slotProbs, float[] targetProbs, float[] actionProbs,
			StepCache[] caches, int maxSteps ){

		Stats s = new Stats();
		int steps = Math.min( chunk.size(), maxSteps );
		if (steps <= 0) return s;
		s.steps = steps;

		net.beginBackward();

		for (int i = 0; i < steps; i++){
			Transition t = chunk.get( i );
			StepCache cache = caches[ i ];

			if (i == 0 && t.recurrentState != null) net.loadState( t.recurrentState );

			t.unpackGrid( grid );
			net.forwardCached( grid, t.inventory, t.hero, cache );

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
					gradient = cache.slotGrad;
					break;
				case Policy.HEAD_TARGET:
					logits = net.targetLogits();
					mask = t.targetMask;
					chosen = t.slotIndex;
					probabilities = targetProbs;
					gradient = cache.targetGrad;
					break;
				default:
					logits = net.actionLogits();
					mask = t.actionMask;
					chosen = t.actionIndex;
					probabilities = actionProbs;
					gradient = cache.actionGrad;
					break;
			}

			java.util.Arrays.fill( gradient, 0f );

			Policy.probabilities( logits, mask, probabilities );

			if (t.demonstration){
				Policy.accumulateImitationGradient( probabilities, mask, t.teacherIndex, demoWeight, gradient );
				cache.valueGrad = 0f;
				continue;
			}

			s.clipBinding |= Policy.accumulatePolicyGradient( probabilities, mask, chosen,
					t.oldLogProbability, t.advantage, clipEpsilon, entropyCoeff, gradient );
			Policy.accumulateImitationGradient( probabilities, mask, t.teacherIndex, teacherWeight, gradient );

			float target = valueTarget( t );
			cache.valueGrad = Policy.valueGradient( net.value(), target, valueGradientClip )
					* valueLossCoefficient;

			s.policyLoss += surrogateLoss( probabilities, chosen, t.oldLogProbability, t.advantage );
			s.valueLoss += Policy.valueLoss( net.value(), target, huberValues );
			s.entropy += Policy.entropy( probabilities );
			s.kl += approximateKL( chosen, t.oldLogProbability, probabilities );
		}

		for (int i = steps - 1; i >= 0; i--){
			Transition t = chunk.get( i );
			StepCache cache = caches[ i ];

			net.backwardStep( t.liveHead == Policy.HEAD_ACTION ? cache.actionGrad : null,
					t.liveHead == Policy.HEAD_SLOT ? cache.slotGrad : null,
					t.liveHead == Policy.HEAD_TARGET ? cache.targetGrad : null,
					cache.valueGrad, cache );
		}

		return s;
	}

	public float baselineMean(){
		return normaliseValues && valueNorm.ready() ? valueNorm.mean() : 0f;
	}

	public float baselineStd(){
		return normaliseValues && valueNorm.ready() ? valueNorm.std() : 1f;
	}

	public float valueTarget( Transition t ){
		return normaliseValues && valueNorm.ready() ? valueNorm.normalize( t.returnValue )
				: t.returnValue;
	}

	static class Stats {
		float policyLoss;
		float valueLoss;
		float entropy;
		float kl;
		boolean clipBinding;
		int steps;
	}

	private Minibatch processMinibatch( List< List< Transition > > chunks, int from, int to ){
		Minibatch result = new Minibatch();
		float policyLoss = 0;
		float valueLoss = 0;
		float entropy = 0;
		float kl = 0;
		float clipped = 0;
		int count = Math.max( 1, to - from );

		network.zeroGrad();

		for (int i = from; i < to; i++){
			List< Transition > chunk = chunks.get( i );
			Stats s = oneChunk( network, chunk, scratchGrid, slotProbs, targetProbs, actionProbs,
					chunkCaches, chunkLength );

			policyLoss += s.policyLoss;
			valueLoss += s.valueLoss;
			entropy += s.entropy;
			kl += s.kl;
			clipped += s.clipBinding ? 1 : 0;
			result.steps += s.steps;
		}

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

	private float surrogateLoss( float[] probabilities, int chosen, float behaviourLogProbability,
			float advantage ){
		float ratio = (float) Math.exp( Policy.logProbability( probabilities, chosen ) - behaviourLogProbability );
		float clipped = Math.max( 1f - clipEpsilon, Math.min( 1f + clipEpsilon, ratio ));
		return -Math.min( ratio * advantage, clipped * advantage );
	}

	private static float approximateKL( int chosen, float behaviourLogProbability, float[] probabilities ){
		float newLog = Policy.logProbability( probabilities, chosen );
		float ratio = (float) Math.exp( newLog - behaviourLogProbability );

		return (ratio - 1f) - (float) Math.log( Math.max( ratio, 1e-8f ) );
	}
}