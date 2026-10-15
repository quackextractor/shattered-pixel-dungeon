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
		/**
		 * The episode has been reset and the seed resolved.
		 *
		 * The seed text is the one the run <em>actually used</em>, not the one requested. An empty
		 * request means the game drew one, and a recorder begun before the reset would write the empty
		 * request and produce a file that can never be verified.
		 */
		void onEpisodeStart( String seedText, String heroClass );

		/**
		 * One decision, taken but <em>not</em> yet applied.
		 *
		 * <p>Separate from {@link #onStep} because a decision is made against the world as it stands and
		 * a step's consequences belong to the world after it. A replay records both, and which side of
		 * the step each field is captured on is not a detail: {@code ReplayRecorder} pins the quickslot
		 * bindings <i>here</i>, because an equip or unequip in this very step rebinds a slot and the
		 * binding recorded afterwards describes a different world.
		 *
		 * <p>That was not hypothetical and it was not the recorder's fault.
		 * {@code ReplayRecorder.record} documents that it is called before {@code SPDEnv.step}, and the
		 * worker called it from {@code onStep} anyway - which the listener could not report, because
		 * {@code onStep} is by definition after. Every trainer recording therefore carried the bindings
		 * the step <i>produced</i> rather than the ones its slot index was chosen against, and a
		 * {@code DROP} is the one action that unbinds its own item, so that step in particular named a
		 * different item on playback.
		 *
		 * @param mode      the mode the decision is being made under
		 * @param secondary the slot index, option index or aim offset, resolved against this world
		 */
		void onDecision( EnvMode mode, Action action, int secondary );

		/**
		 * One completed step.
		 *
		 * <p>Afterwards, and only afterwards: the position is where the hero ended up, and the reward is
		 * what the step earned. Both are properties of the world the step produced.
		 *
		 * @param heroPosition where the hero ended up, after the step
		 */
		void onStep( int heroPosition, float reward );
	}

	private Listener listener;

	/**
	 * Optional world trace for this episode, written as the run happens.
	 *
	 * <p>Null unless something asked for one, so an ordinary training run pays nothing. Set by
	 * {@link #trace(String)} from a diagnostic, and the point is to have the *recording* side of a
	 * comparison: {@code worldtrace} can only describe what replaying produces, so a run and its
	 * replay can be told apart but never compared directly.
	 */
	private com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot trace;

	/** Where {@link #trace} writes it. Null with {@link #trace} null, i.e. off. */
	private String tracePath;
	/** Counts traced episodes, so each trace file is distinct even when seeds repeat. */
	private int tracedEpisodes;

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

		if (listener != null){
			listener.onEpisodeStart( env.seedText(), heroClass.name() );
		}

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

			Transition snapshot = snapshot( env );

			if (wantsByDraw( stepIndex )) record.retain( stepIndex, snapshot );

			tail.addLast( new Tail( stepIndex, snapshot ) );
			while (tail.size() > TAIL_STEPS){
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

			if (listener != null) listener.onStep( heroPosition, reward );

			record.add( reward, network.value(), isTerminal, liveHead, actionIndex, slotIndex,
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

	/** The Bernoulli draw. Position is not part of it, so this is uniform over steps. */
	private boolean wantsByDraw( int stepIndex ){
		if (record.sampledCount() >= maxSampled) return false;
		return samplingRng.nextFloat() < sampleRate;
	}

	/**
	 * Copies the current observation, and the state it is read in, into a pooled transition.
	 *
	 * <p>The state snapshot is the part that matters. The update replays each observation through
	 * current weights, and without the state that replay happens under whatever hidden state the
	 * previously processed sample left behind — which after a shuffle is an unrelated observation.
	 * `replayprobe` measures the resulting error at up to 20% of the value's magnitude.
	 *
	 * <p>Taken here, before the step, because the forward pass for this state has already run at the
	 * top of the loop; the next iteration's forward is what advances the state. Taking it after the
	 * step would capture the *next* state's hidden vector and be off by one step, which is the same
	 * class of ordering bug as the hero position one below.
	 */
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
			case INVENTORY: return Policy.HEAD_SLOT;
			case TARGETING: return Policy.HEAD_TARGET;
			default:        return Policy.HEAD_ACTION;
		}
	}

	/** Told about every step, for a replay recorder. Null, which is the default, costs one branch. */
	public void listener( Listener listener ){
		this.listener = listener;
	}

	/**
	 * Collects a world trace of this episode and writes it to {@code path} when the run ends.
	 *
	 * <p>Diagnostic, and it exists for one comparison nothing else could make. {@code worldtrace} traces
	 * what <em>replaying</em> a recording produces; this traces what <em>making</em> it produced. Two
	 * trainer recordings diverge at a step where the recorded and replayed floors agree cell for cell,
	 * so the difference has to be in something level generation read - the special-room pools among it -
	 * and that can only be settled by putting both sides of the comparison on disk.
	 *
	 * <p>Null unless asked for, so an ordinary training run pays one branch per step. The trace is keyed by
	 * step index exactly as {@code WorldSnapshot} keys it, so the two files line up row for row.
	 */
	public void trace( String path ){
		tracePath = (path == null || path.trim().isEmpty()) ? null : path;
		if (tracePath == null) trace = null;

		else trace = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot()
				.cells( false );
	}

	/** Writes the collected trace, if {@link #trace(String)} asked for one. */
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
