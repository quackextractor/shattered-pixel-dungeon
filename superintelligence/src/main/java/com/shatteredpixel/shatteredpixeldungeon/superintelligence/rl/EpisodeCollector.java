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

/**
 * Collects one episode on behalf of the policy, and computes its advantages before returning.
 *
 * Replaces {@link PPO#collect}, which stored a bounded chunk of transitions and left the advantage
 * recursion to whoever called {@code update}. That cannot cross a process boundary: the recursion
 * needs consecutive steps, and a worker that kept only every n-th one would compute advantages from
 * non-adjacent steps - numbers that look like advantages, are finite, and are wrong. So the backward
 * pass happens here, while the whole episode is still resident as scalars.
 *
 * Three things are done differently from {@code collect}, all of them deliberate:
 *
 * <ul>
 * <li><b>One forward pass per step, not two.</b> {@code collect} forwards again after every step to
 * fill {@code nextValue}, but the GAE recursion reads {@code nextValue} at exactly one index and
 * takes every other bootstrap from the next step's own {@code value}. An episode of n steps needs
 * n+1 forwards. The extra pass also advanced the LSTM over the post-step observation, so every
 * observation was absorbed into the recurrent state twice.
 *
 * <li><b>Sampling during collection, not after GAE.</b> The backward pass reads only scalars, so
 * which observations are kept cannot change the numbers it writes. Keeping every observation until
 * the episode ended would cost 1.9 GB on a long one.
 *
 * <li><b>The last K steps are always retained.</b> The death penalty is -100 and the depth reward is
 * +10, both landing on single steps. Under uniform sampling a terminal step survives with
 * probability equal to the sample rate, so most episodes would contribute no terminal signal at all.
 * γλ = 0.9405 supports an advantage over ~17 steps, so keeping the tail is cheap and is where the
 * gradient has the most to say.
 * </ul>
 *
 * Not thread-safe: one collector, one env, one episode.
 */
public class EpisodeCollector {

	/**
	 * Steps at the end of every episode that are retained regardless of the sample rate.
	 *
	 * Rounded up from 1/(1-γλ) ≈ 17 at γ=0.99, λ=0.95 - the span over which an advantage is
	 * meaningfully supported. Fixed rather than derived from the hyper-parameters so that changing
	 * γ or λ does not silently change how much of every episode is kept.
	 */
	public static final int TAIL_STEPS = 20;

	/** Ceiling on retained observations for one episode, ~98 MB at the current observation size. */
	public static final int DEFAULT_MAX_SAMPLED = 2048;

	private final EnvConfig config;
	private final Network network;
	private final Random rng;
	private final Random samplingRng;

	private final float gamma;
	private final float lambda;
	private final float sampleRate;
	private final int maxSampled;

	private final int gridSize;
	private final int inventorySize;
	private final int heroSize;

	/**
	 * Told about every step as it is taken.
	 *
	 * Exists so a caller can record a replay of what the policy actually did. Without it the collector
	 * would play an episode that nobody could watch, and the replay feature would quietly become a
	 * second implementation of the same loop - which is how they drift apart.
	 */
	public interface Listener {
		void onStep( EnvMode mode, Action action, int secondary, int heroPosition, float reward );
	}

	private Listener listener;

	private final EpisodeRecord record = new EpisodeRecord();

	/**
	 * The last {@link #TAIL_STEPS} steps' transitions, held speculatively.
	 *
	 * Which steps are the episode's last ones is not known until it ends, so every step passes
	 * through here and only the survivors are kept. Bounded at 20 transitions, which is ~1 MB, so
	 * holding them costs nothing - unlike keeping every observation, which is what would otherwise
	 * be the only way to guarantee the tail.
	 */
	private final java.util.ArrayDeque<Tail> tail = new java.util.ArrayDeque<>();

	//one probability buffer per head. The three heads have different widths and the loss reads the
	//probability vector against that head's own mask, so a shared buffer sized for the widest head
	//would run off the end of the narrower masks.
	private final float[] actionProbs;
	private final float[] slotProbs;
	private final float[] targetProbs;

	public EpisodeCollector( EnvConfig config, Network network, Random rng, Random samplingRng,
			float gamma, float lambda, float sampleRate ){
		this( config, network, rng, samplingRng, gamma, lambda, sampleRate, DEFAULT_MAX_SAMPLED );
	}

	public EpisodeCollector( EnvConfig config, Network network, Random rng, Random samplingRng,
			float gamma, float lambda, float sampleRate, int maxSampled ){

		this.config = config;
		this.network = network;
		this.rng = rng;
		this.samplingRng = samplingRng;
		this.gamma = gamma;
		this.lambda = lambda;
		this.sampleRate = sampleRate;
		this.maxSampled = maxSampled;

		this.gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		this.inventorySize = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		this.heroSize = HeroEncoder.FEATURES;

		this.actionProbs = new float[ Action.size() ];
		this.slotProbs = new float[ config.maxSlots ];
		this.targetProbs = new float[ ActionMapper.TARGET_COUNT ];
	}

	/**
	 * Plays one episode to its end and returns its record.
	 *
	 * @return a record holding every step's scalars and the sampled subset's observations, with
	 *         advantages and returns already computed. The caller owns the retained transitions and
	 *         must release them.
	 */
	public EpisodeRecord run( SPDEnv env, String seed, HeroClass heroClass ){
		record.release();
		tail.clear();

		env.reset( seed, heroClass );
		network.resetState();

		while (env.running()){
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
			boolean isTerminal = env.endedNaturally();

			int stepIndex = record.stepCount();

			//one snapshot per step, filled before the step because the encoder's buffers are only
			//valid until the env advances. Whether it is retained is decided twice and independently:
			//now, by the draw, and again at the end, by its position in the tail.
			Transition snapshot = snapshot( env );

			if (wantsByDraw( stepIndex )) record.retain( stepIndex, snapshot );

			//every step also enters a short rolling tail, so the last TAIL_STEPS steps are
			//recoverable however the draw went. The tail is bounded, which is what stops this from
			//being "keep everything".
			tail.addLast( new Tail( stepIndex, snapshot ) );
			while (tail.size() > TAIL_STEPS){
				Transition dropped = tail.removeFirst().transition;
				if (!record.isRetained( dropped )) dropped.release();
			}

			Action action = Action.fromIndex( actionIndex );
			int secondary = (liveHead == Policy.HEAD_TARGET) ? targetIndex : slotIndex;
			EnvMode mode = env.mode();
			int heroPosition = env.heroPosition();

			float reward = (float) env.step( action, secondary );

			if (listener != null) listener.onStep( mode, action, secondary, heroPosition, reward );

			record.add( reward, network.value(), isTerminal, liveHead, actionIndex, slotIndex,
					logProbability );

			//the bootstrap for a truncated episode needs the value of the state this step led to, and
			//every other step's bootstrap is the next step's own value - so one extra forward for the
			//whole episode rather than one per step
			if (!env.running()){
				network.forward( env.grid(), env.inventory(), env.heroFeatures() );
				record.finalValue( network.value() );
			}
		}

		//the last TAIL_STEPS steps are now known, and a step already kept by the draw is not kept twice
		for (Tail held : tail){
			if (!record.isRetained( held.transition )) record.retain( held.step, held.transition );
		}
		tail.clear();

		record.computeAdvantages( gamma, lambda );
		network.resetState();

		return record;
	}

	/** The Bernoulli draw. Position is not part of it, so this is uniform over steps. */
	private boolean wantsByDraw( int stepIndex ){
		if (record.sampledCount() >= maxSampled) return false;
		return samplingRng.nextFloat() < sampleRate;
	}

	/** Copies the current observation out of the env into a pooled transition. */
	private Transition snapshot( SPDEnv env ){
		Transition t = take();
		t.packGrid( env.grid() );
		System.arraycopy( env.inventory(), 0, t.inventory, 0, inventorySize );
		System.arraycopy( env.heroFeatures(), 0, t.hero, 0, heroSize );
		System.arraycopy( env.actionMask(), 0, t.actionMask, 0, t.actionMask.length );
		System.arraycopy( env.slotMask(), 0, t.slotMask, 0, t.slotMask.length );
		System.arraycopy( env.targetMask(), 0, t.targetMask, 0, t.targetMask.length );
		return t;
	}

	/** A step held in the rolling tail, paired with the step index it belongs to. */
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
				Action.size(), config.maxSlots, ActionMapper.TARGET_COUNT );
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

	/** Told about every step, for a replay recorder. Null, which is the default, costs one branch. */
	public void listener( Listener listener ){
		this.listener = listener;
	}
}