package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EpisodeRecord {

	private float[] rewards = new float[ 256 ];
	private float[] values = new float[ 256 ];
	private float[] terminal = new float[ 256 ];
	private float[] advantages = new float[ 256 ];
	private float[] returns = new float[ 256 ];

	private int[] liveHead = new int[ 256 ];
	private int[] actionIndex = new int[ 256 ];
	private int[] slotIndex = new int[ 256 ];
	private float[] oldLogProbability = new float[ 256 ];

	private final ArrayList<Transition> sampled = new ArrayList<>();
	private int[] sampledSteps = new int[ 64 ];

	private int steps;

	private float finalValue;

	private int episodeId = -1;

	public int stepCount(){
		return steps;
	}

	public int sampledCount(){
		return sampled.size();
	}

	public List<Transition> sampledTransitions(){
		return Collections.unmodifiableList( sampled );
	}

	public float finalValue(){
		return finalValue;
	}

	public int sampledStepAt( int i ){
		return sampledSteps[ i ];
	}

	public float advantageAt( int i ){ return advantages[ i ]; }

	public void episode( int id ){
		this.episodeId = id;
		for (int i = 0; i < sampled.size(); i++){
			sampled.get( i ).episodeId = id;
			sampled.get( i ).stepIndex = sampledSteps[ i ];
		}
	}

	public void add( float reward, float value, boolean isTerminal, int head, int action, int slot,
			float logProbability ){

		ensure( steps );

		rewards[ steps ] = reward;
		values[ steps ] = value;
		terminal[ steps ] = isTerminal ? 1f : 0f;
		liveHead[ steps ] = head;
		actionIndex[ steps ] = action;
		slotIndex[ steps ] = slot;
		oldLogProbability[ steps ] = logProbability;
		steps++;
	}

	public void retain( int index, Transition t ){
		if (isRetained( t )) return;

		sampled.add( t );

		if (sampledSteps.length < sampled.size()) sampledSteps = grow( sampledSteps, sampled.size() );
		sampledSteps[ sampled.size() - 1 ] = index;
	}

	public boolean isRetained( Transition t ){
		for (int i = 0; i < sampled.size(); i++){
			if (sampled.get( i ) == t ) return true;
		}
		return false;
	}

	public void computeAdvantages( float gamma, float lambda ){
		if (steps == 0) return;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, steps, finalValue, gamma, lambda,
				advantages, returns );

		for (int i = 0; i < sampled.size(); i++){
			Transition t = sampled.get( i );
			int step = sampledSteps[ i ];
			t.advantage = advantages[ step ];
			t.returnValue = returns[ step ];
			t.liveHead = liveHead[ step ];
			t.episodeId = episodeId;
			t.stepIndex = step;
			t.actionIndex = actionIndex[ step ];
			t.slotIndex = slotIndex[ step ];
			t.oldLogProbability = oldLogProbability[ step ];
			t.value = values[ step ];
			t.terminal = terminal[ step ] != 0f;
		}
	}

	public void finalValue( float v ){
		finalValue = v;
	}

	public void release(){
		for (Transition t : sampled) t.release();
		sampled.clear();
		steps = 0;
		episodeId = -1;
	}

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