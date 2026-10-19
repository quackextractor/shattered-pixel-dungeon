package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import java.util.List;
import java.util.Random;


public class EpisodeCollector {


	public static final int TAIL_STEPS = 20;


	public int tailSteps = TAIL_STEPS;


	public EpisodeCollector tailSteps( int steps ){
		this.tailSteps = steps;
		return this;
	}

	public int chunkLength(){
		return chunkLength;
	}


	public static final int DEFAULT_MAX_SAMPLED = 2048;

	private final EnvConfig config;
	private final Network network;
	private final Random rng;
	private final Random samplingRng;

	private final float gamma;
	private final float lambda;
	private final float sampleRate;
	private final int maxSampled;
	private final int chunkLength;

	private final int gridSize;
	private final int inventorySize;
	private final int heroSize;


	public interface Listener {

		void onEpisodeStart( String seedText, String heroClass );


		void onDecision( EnvMode mode, Action action, int secondary );


		default void onStep( int heroPosition, float reward ){}

		default void onStep( int heroPosition, float reward, boolean accepted ){
			onStep( heroPosition, reward );
		}
	}

	private Listener listener;


	private com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot trace;


	private String tracePath;

	private int tracedEpisodes;

	private final EpisodeRecord record = new EpisodeRecord();


	private int episodes;


	private final java.util.ArrayDeque<Tail> tail = new java.util.ArrayDeque<>();

	private final float[] actionProbs;
	private final float[] slotProbs;
	private final float[] targetProbs;

	public static final int DEFAULT_CHUNK_LENGTH = 16;

	private final ContiguousChunkSampler sampler = new ContiguousChunkSampler();

	public EpisodeCollector( EnvConfig config, Network network, Random rng, Random samplingRng,
			float gamma, float lambda, float sampleRate ){
		this( config, network, rng, samplingRng, gamma, lambda, sampleRate, DEFAULT_MAX_SAMPLED );
	}

	public EpisodeCollector( EnvConfig config, Network network, Random rng, Random samplingRng,
			float gamma, float lambda, float sampleRate, int maxSampled ){
		this( config, network, rng, samplingRng, gamma, lambda, sampleRate, maxSampled,
				DEFAULT_CHUNK_LENGTH );
	}

	public EpisodeCollector( EnvConfig config, Network network, Random rng, Random samplingRng,
			float gamma, float lambda, float sampleRate, int maxSampled, int chunkLength ){

		this.config = config;
		this.network = network;
		this.rng = rng;
		this.samplingRng = samplingRng;
		this.gamma = gamma;
		this.lambda = lambda;
		this.sampleRate = sampleRate;
		this.maxSampled = maxSampled;
		this.chunkLength = Math.max( 1, chunkLength );

		this.gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		this.inventorySize = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		this.heroSize = HeroEncoder.FEATURES;

		this.actionProbs = new float[ Action.size() ];
		this.slotProbs = new float[ config.maxSlots ];
		this.targetProbs = new float[ ActionMapper.TARGET_COUNT ];
	}


	public EpisodeRecord run( SPDEnv env, String seed, HeroClass heroClass ){
		record.release();
		tail.clear();
		sampler.reset();

		env.reset( seed, heroClass );
		network.resetState();
		record.episode( ++episodes );

		if (listener != null){
			listener.onEpisodeStart( env.seedText(), heroClass.name() );
		}

		while (env.running()){
			Transition snapshot = snapshot( env );
			network.forward( env.grid(), env.inventory(), env.heroFeatures() );

			int liveHead = headFor( env.mode() );
			int actionIndex = Policy.sample( network.actionLogits(), env.actionMask(), rng );
			int slotIndex = Policy.sample( network.slotLogits(), env.slotMask(), rng );
			int targetIndex = Policy.sample( network.targetLogits(), env.targetMask(), rng );

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
			float logProbability = Policy.logProbability( probabilitiesFor( liveHead ), chosen );

			int stepIndex = record.stepCount();

			if (wantsByDraw( stepIndex )) record.retain( stepIndex, snapshot );

			tail.addLast( new Tail( stepIndex, snapshot ) );
			while (tail.size() > tailSteps ){
				Transition dropped = tail.removeFirst().transition;
				if (!record.isRetained( dropped )) dropped.release();
			}

		Action action = Action.fromIndex( actionIndex );
		int secondary = (liveHead == Policy.HEAD_TARGET) ? targetIndex : slotIndex;

		EnvMode mode = env.mode();

		if ( listener != null ) listener.onDecision( mode, action, secondary );

		float reward = (float) env.step( action, secondary );

		if (trace != null) trace.sample( "step", record.stepCount() );

			int heroPosition = env.heroPosition();

			if (listener != null) listener.onStep( heroPosition, reward, env.lastStepActed() );

			record.add( reward, network.value(), env.endedNaturally(), liveHead, actionIndex, slotIndex,
					logProbability );

			if (!env.running()){
				network.forward( env.grid(), env.inventory(), env.heroFeatures() );
				record.finalValue( network.value() );
			}
		}

		for (Tail held : tail){
			if (!record.isRetained( held.transition )) record.retain( held.step, held.transition );
		}
		tail.clear();

		record.computeAdvantages( gamma, lambda );
		network.resetState();

		flushTrace( env.seedText() );

		return record;
	}


	private boolean wantsByDraw( int stepIndex ){
		return sampler.shouldSample( stepIndex, 0, tailSteps, sampleRate, chunkLength,
				record.sampledCount(), maxSampled, samplingRng );
	}


	private Transition snapshot( SPDEnv env ){
		Transition t = take();
		t.packGrid( env.grid() );
		System.arraycopy( env.inventory(), 0, t.inventory, 0, inventorySize );
		System.arraycopy( env.heroFeatures(), 0, t.hero, 0, heroSize );
		System.arraycopy( env.actionMask(), 0, t.actionMask, 0, t.actionMask.length );
		System.arraycopy( env.slotMask(), 0, t.slotMask, 0, t.slotMask.length );
		System.arraycopy( env.targetMask(), 0, t.targetMask, 0, t.targetMask.length );
		network.saveState( t.recurrentState );
		return t;
	}


	private static class Tail {
		final int step;
		final Transition transition;

		Tail( int step, Transition transition ){
			this.step = step;
			this.transition = transition;
		}
	}

	private Transition take(){
		return Transition.take( gridSize, inventorySize, heroSize,
				Action.size(), config.maxSlots, ActionMapper.TARGET_COUNT, network.stateSize() );
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
			case INVENTORY:
			case TALENT: return Policy.HEAD_SLOT;
			case TARGETING: return Policy.HEAD_TARGET;
			default:        return Policy.HEAD_ACTION;
		}
	}


	public void listener( Listener listener ){
		this.listener = listener;
	}


	public void trace( String path ){
		tracePath = (path == null || path.trim().isEmpty()) ? null : path;
		if (tracePath == null) trace = null;

		else trace = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot()
				.cells( false );
	}


	private void flushTrace( String seed ){
		if (trace == null || tracePath == null) return;
		try {

			trace.writeTo( java.nio.file.Paths.get(
					tracePath + "-" + (++tracedEpisodes) + "-" + seed + ".txt" ));
		} catch (java.io.IOException e){
			System.err.println( "[WARN] could not write the world trace to " + tracePath + ": "
					+ e.getMessage() );
		}
		trace = null;
	}
}
