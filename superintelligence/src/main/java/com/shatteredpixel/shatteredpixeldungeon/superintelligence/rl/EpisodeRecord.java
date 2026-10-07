package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One episode of experience, split into what GAE needs and what the trainer needs.
 *
 * The split is the whole point. GAE reads only scalars, about 21 bytes a step, so every step's
 * scalars are kept and the advantages are computed over the complete episode at the end. The
 * observation is 49 KB and 96% of a transition, so only a sampled fraction of those is retained.
 * That is what lets a worker finish an episode of any length without ever holding the episode's
 * observations, which at 40,000 steps would be 1.9 GB.
 *
 * Sampling is drawn during collection rather than after GAE, which produces identical advantages:
 * the backward pass never reads an observation, so which observations are kept cannot change the
 * numbers it writes. See {@link EpisodeCollector}.
 *
 * Not thread-safe and not meant to be - one record belongs to one worker's one episode.
 */
public class EpisodeRecord {

	/** Every step's scalars, index-aligned. Grown on demand, never shrunk within an episode. */
	private float[] rewards = new float[ 256 ];
	private float[] values = new float[ 256 ];
	private float[] terminal = new float[ 256 ];
	private float[] advantages = new float[ 256 ];
	private float[] returns = new float[ 256 ];

	private int[] liveHead = new int[ 256 ];
	private int[] actionIndex = new int[ 256 ];
	private int[] slotIndex = new int[ 256 ];
	private float[] oldLogProbability = new float[ 256 ];

	/** Retained observations, each paired with the step index it belongs to. */
	private final ArrayList<Transition> sampled = new ArrayList<>();
	private int[] sampledSteps = new int[ 64 ];

	private int steps;

	/** Value of the state the last step led to, so a truncated episode can bootstrap. */
	private float finalValue;

	//total steps the episode took, including ones whose observations were dropped
	public int stepCount(){
		return steps;
	}

	/** Steps whose observations were retained, which is what ships to the trainer. */
	public int sampledCount(){
		return sampled.size();
	}

	/** Unmodifiable: the collector hands these to the trainer's serialiser and does not keep them. */
	public List<Transition> sampledTransitions(){
		return Collections.unmodifiableList( sampled );
	}

	/** Value of the state the episode's last step led to, which bootstraps a truncated episode. */
	public float finalValue(){
		return finalValue;
	}

	/** The step index the i'th retained transition belongs to. */
	public int sampledStepAt( int i ){
		return sampledSteps[ i ];
	}

	public float advantageAt( int i ){ return advantages[ i ]; }

	/**
	 * Appends one step's scalars.
	 *
	 * Whether the step's observation is kept is a separate decision, made by {@link #retain}. It used
	 * to be a flag on this call, which was wrong twice over: it duplicated a fact already recorded by
	 * {@code retain}, and it was passed as false from the one call site that could pass it - the
	 * retention happens first, then the step is appended.
	 */
	public void add( float reward, float value, boolean isTerminal, int head, int action, int slot,
			float logProbability ){

		ensure( steps );

		//the arrays are grown together and kept index-aligned, so the GAE pass at the end of the
		//episode can read reward, value and terminal for any step without knowing which of them a
		//sampled transition happens to be
		rewards[ steps ] = reward;
		values[ steps ] = value;
		terminal[ steps ] = isTerminal ? 1f : 0f;
		liveHead[ steps ] = head;
		actionIndex[ steps ] = action;
		slotIndex[ steps ] = slot;
		oldLogProbability[ steps ] = logProbability;
		steps++;
	}

	/**
	 * Retains a step's transition, keyed by the step index it belongs to.
	 *
	 * A step can arrive here twice - once from the uniform draw during collection and once from the
	 * episode's tail - so this is idempotent on the transition itself. Two copies of one step would
	 * weight it twice in the gradient, which is a different estimator rather than a noisy one.
	 */
	public void retain( int index, Transition t ){
		if (isRetained( t )) return;

		sampled.add( t );

		if (sampledSteps.length < sampled.size()) sampledSteps = grow( sampledSteps, sampled.size() );
		sampledSteps[ sampled.size() - 1 ] = index;
	}

	/** Whether this transition is already retained. Identity, not value - one object, one step. */
	public boolean isRetained( Transition t ){
		for (int i = 0; i < sampled.size(); i++){
			if (sampled.get( i ) == t ) return true;
		}
		return false;
	}

	/**
	 * Walks the whole episode backwards, writing the advantage and return for every step.
	 *
	 * Called once, at the end of the episode. Doing it here rather than in the trainer is what allows
	 * unsampled steps to exist at all: the recursion needs its neighbours, and the neighbours are the
	 * scalars, which were all kept.
	 */
	public void computeAdvantages( float gamma, float lambda ){
		if (steps == 0) return;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, steps, finalValue, gamma, lambda,
				advantages, returns );

		//the retained transitions are the only place these two end up, and the trainer reads them
		//from there rather than recomputing anything
		for (int i = 0; i < sampled.size(); i++){
			Transition t = sampled.get( i );
			int step = sampledSteps[ i ];
			t.advantage = advantages[ step ];
			t.returnValue = returns[ step ];
			t.liveHead = liveHead[ step ];
			t.actionIndex = actionIndex[ step ];
			t.slotIndex = slotIndex[ step ];
			t.oldLogProbability = oldLogProbability[ step ];
			t.terminal = terminal[ step ] != 0f;
		}
	}

	public void finalValue( float v ){
		finalValue = v;
	}

	/** Releases the retained transitions. Safe to call twice. */
	public void release(){
		for (Transition t : sampled) t.release();
		sampled.clear();
		steps = 0;
	}

	// --------------------------------------------------------------------------- growth

	/** Grows every per-step array together, so the GAE pass can index them in lockstep. */
	private void ensure( int index ){
		if (index < rewards.length) return;

		int size = rewards.length * 2;
		rewards = grow( rewards, size );
		values = grow( values, size );
		terminal = grow( terminal, size );
		advantages = grow( advantages, size );
		returns = grow( returns, size );
		liveHead = grow( liveHead, size );
		actionIndex = grow( actionIndex, size );
		slotIndex = grow( slotIndex, size );
		oldLogProbability = grow( oldLogProbability, size );
	}

	private static float[] grow( float[] existing, int size ){
		float[] out = new float[ size ];
		System.arraycopy( existing, 0, out, 0, existing.length );
		return out;
	}

	private static int[] grow( int[] existing, int size ){
		int[] out = new int[ size ];
		System.arraycopy( existing, 0, out, 0, existing.length );
		return out;
	}
}