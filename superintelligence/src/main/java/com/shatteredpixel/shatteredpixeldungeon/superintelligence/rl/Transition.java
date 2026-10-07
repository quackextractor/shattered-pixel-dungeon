package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayDeque;

/**
 * One agent decision, stored for a policy-gradient update.
 *
 * The observation is packed rather than kept as floats. Every spatial plane is binary - a tile is
 * either visible, or a wall, or it is not - so a float grid would waste 32x the memory. Packed, a
 * step costs one byte per plane per cell, which is what makes a rollout long enough to be worth
 * training on: at a 48x48 grid over 20 planes that is 46KB per step instead of 184KB.
 *
 * Only the behaviour log-probability is kept from the forward pass, not the logits or the
 * activations. That is deliberate. An exact recurrent gradient needs every timestep's
 * pre-activation hidden state retained until the update, and at a few hundred steps per episode
 * across a thousand episodes that is gigabytes. The update therefore replays each stored
 * observation through the current weights, which is standard for recurrent PPO and costs one
 * extra forward pass per sample.
 */
public class Transition {

	/** Packed spatial planes, spatialChannels * gridWidth * gridWidth bytes. */
	public byte[] grid;

	public float[] inventory;
	public float[] hero;

	public float[] actionMask;
	public float[] slotMask;
	public float[] targetMask;

	/**
	 * The LSTM state this step was decided under: {@code [hidden | cell]}, or {@code null}.
	 *
	 * <p><b>The most important field in this class, and it was missing for the whole of the first
	 * implementation.</b> The update replays each stored observation through the current weights, and
	 * the replay's hidden state used to be whatever the previously processed sample left behind — a
	 * different timestep of a different episode, because {@link PPO#update} shuffles. Measured with
	 * {@code replayprobe}: replaying in collection order reproduces the rollout's value to 0.000000,
	 * and replaying in shuffled order drifts by up to 20% of the value's magnitude.
	 *
	 * <p>So the policy gradient was being computed against a value and a distribution the behaviour
	 * policy never saw. The ratio {@code pi_new / pi_old} was formed across two different states, which
	 * is not a small perturbation — it is a different objective, and it is the whole reason the clip
	 * fraction sat at 0.84 on the first update.
	 *
	 * <p>Carrying it also decouples the samples: once a transition restores its own state, sample
	 * <i>i</i> no longer depends on sample <i>i-1</i>, which is what makes a parallel update possible
	 * at all. Before this, "parallelise the minibatch" was blocked by a correctness bug rather than by
	 * anything to do with threads.
	 */
	public float[] recurrentState;

	/** Which head produced this step's decision; see Policy.HEAD_*. */
	public int liveHead;

	public int actionIndex;
	public int slotIndex;

	/** log pi_behaviour(chosen | state), from the rollout's forward pass. */
	public float oldLogProbability;

	/** Critic estimate of the state this decision was made in. */
	public float value;

	/** Reward received after this step. */
	public float reward;

	/** True when the episode ended on this step by death or victory, so no bootstrap applies. */
	public boolean terminal;

	/** Value estimate of the state this step led to, for bootstrapping a truncated episode. */
	public float nextValue;

	/** Filled in by the update. */
	public float returnValue;
	public float advantage;

	private static final ArrayDeque<Transition> POOL = new ArrayDeque<>();
	private static final int MAX_POOLED = 8192;

	private boolean pooled = false;

	/**
	 * Takes a pooled transition, or makes one.
	 *
	 * @param recurrentStateSize floats the LSTM state needs, or 0 for a transition that does not carry
	 *                           one. Zero is legal rather than an error because some checks build
	 *                           synthetic transitions and have no network to snapshot.
	 */
	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount, int recurrentStateSize ){

		Transition t = POOL.poll();
		if (t == null) t = new Transition();

		t.grid    = fit( t.grid,    gridSize );
		t.inventory = fit( t.inventory, inventorySize );
		t.hero    = fit( t.hero,    heroSize );
		t.actionMask = fit( t.actionMask, actionCount );
		t.slotMask   = fit( t.slotMask,   slotCount );
		t.targetMask = fit( t.targetMask, targetCount );
		t.recurrentState = recurrentStateSize > 0
				? fit( t.recurrentState, recurrentStateSize )
				: null;

		t.oldLogProbability = 0;
		t.value = 0;
		t.reward = 0;
		t.nextValue = 0;
		t.returnValue = 0;
		t.advantage = 0;
		t.terminal = false;
		t.liveHead = Policy.HEAD_ACTION;
		t.actionIndex = 0;
		t.slotIndex = 0;
		t.pooled = false;

		return t;
	}

	/**
	 * As {@link #take} without the recurrent state.
	 *
	 * <p>Retained for the callers that have no network to snapshot — the synthetic transitions in the
	 * checks. Prefer the six-argument form in production code: a transition with a {@code null} state
	 * replays under whatever the previous sample left behind, which is the bug this field fixes.
	 */
	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount ){
		return take( gridSize, inventorySize, heroSize, actionCount, slotCount, targetCount, 0 );
	}

	private static float[] fit( float[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new float[ len ];
	}

	private static byte[] fit( byte[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new byte[ len ];
	}

	/** Packs a float grid, whose values are all 0 or 1, into bytes. */
	public void packGrid( float[] src ){
		for (int i = 0; i < grid.length; i++){
			grid[ i ] = (byte) (src[ i ] > 0.5f ? 1 : 0 );
		}
	}

	/** Unpacks back into a float grid for the update's forward pass. */
	public void unpackGrid( float[] dst ){
		for (int i = 0; i < grid.length && i < dst.length; i++){
			dst[ i ] = grid[ i ] != 0 ? 1f : 0f;
		}
	}

	/** Returns this transition to the pool. Safe to call twice. */
	public void release(){
		if (pooled) return;
		pooled = true;
		if (POOL.size() < MAX_POOLED) POOL.addLast( this );
	}
}