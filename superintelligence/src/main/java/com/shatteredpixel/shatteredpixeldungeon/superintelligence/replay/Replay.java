package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

/**
 * A recorded run: the seed plus the exact sequence of decisions that produced it.
 *
 * research.md: "your headless training loop must log the exact sequence of HeroAction instances
 * chosen by the AI. To watch the replay, you will launch the fully rendered desktop module,
 * initialize the DungeonSeed with the saved seed, and intercept the InputHandler to feed it your
 * logged action array sequentially instead of waiting for human input."
 *
 * Actions are stored by name rather than by index. Reordering the Action enum would otherwise
 * silently turn every previously recorded run into a different run, which is the kind of bug that
 * is very hard to notice because the replay still plays - just wrongly.
 *
 * The sequence is complete rather than reconstructed. A replay is only meaningful if replaying it
 * against the same seed reproduces the run exactly, and that only holds if the decisions, not
 * their consequences, are what got recorded.
 */
public class Replay {

	/** Header written at the top of every replay file. */
	public static final String MAGIC = "SPD-REPLAY";
	public static final int VERSION = 1;

	public String seedText = "";
	public String heroClass = "WARRIOR";
	public int challenges = 0;

	/** Score the run achieved, for ranking the best run per seed. */
	public double score;

	/** Deepest floor reached. */
	public int depth;

	/** Hero turns taken. */
	public int turns;

	/** Set when the replay was captured by a trainer generation. */
	public int generation;

	/**
	 * Per-floor turn cap in force when the run was recorded.
	 *
	 * Recorded because the cap is what truncates most early runs: replaying with a different one
	 * would run past the point the recording ended, and the run would not reproduce.
	 */
	public int turnLimitPerFloor = 1500;

	/**
	 * Step count the header declares.
	 *
	 * Kept separate from {@link #steps} because the two can disagree: a recording written by an
	 * interrupted process, or one copied incompletely. {@code read} rejects that; {@code readHeader}
	 * reports it through {@link #truncatedAt} instead, since a catalog should list a damaged file
	 * rather than hide it.
	 */
	public int declaredSteps;

	/** Steps actually present, or -1 when unknown. See {@link ReplayIO#readHeader}. */
	public int truncatedAt = -1;

	/** One entry per decision the agent made. */
	public final java.util.ArrayList<Step> steps = new java.util.ArrayList<>();

	/** A single agent decision, plus what it produced. */
	public static class Step {

		/** {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action} name. */
		public String action;

		/** Slot index, option index or target offset, depending on the mode at the time. */
		public int slot;

		/** Env mode name before this step, recorded so a playback can assert it agrees. */
		public String mode;

		/** Hero position after the step. Diagnostics only; playback ignores it. */
		public int heroPos;

		/** Running reward after the step. Diagnostics only. */
		public double reward;

		/**
		 * Quickslot bindings in force when this step was recorded, as {@code ItemClass:slot} pairs.
		 *
		 * <p>Recorded per step, and only for steps that name a slot, because a slot index means nothing
		 * without them. {@code ActionMapper.refreshSlots} places quickslot-bound items into their bound
		 * slot before filling the gaps, so the same index resolves to a different item depending on the
		 * bindings: with the Waterskin bound to quickslot 1 it took slot 0, and unbound it left slot 0 to
		 * the VelvetPouch. A {@code DROP} on slot 0 then discarded a different item in each environment,
		 * which changed the backpack's capacity and every later decision built on it.
		 *
		 * <p>Per step rather than per run, because the bindings change during a run: equipping and
		 * unequipping an item rebinds a slot. One snapshot would be right only until the first equip.
		 *
		 * <p>Empty when the step names no slot, and on recordings made before this field existed.
		 */
		public String quickslots = "";

		public Step( String action, int slot, String mode, int heroPos, double reward ){
			this.action = action;
			this.slot = slot;
			this.mode = mode;
			this.heroPos = heroPos;
			this.reward = reward;
		}

		public Step() {}
	}

	public void add( String action, int slot, String mode, int heroPos, double reward ){
		steps.add( new Step( action, slot, mode, heroPos, reward ) );
	}

	public int length(){
		return steps.size();
	}

	/** Total score, kept in sync with the last recorded reward. */
	public void captureOutcome( double score, int depth, int turns, int generation ){
		this.score = score;
		this.depth = depth;
		this.turns = turns;
		this.generation = generation;
	}

	public void reset(){
		steps.clear();
		score = 0;
		depth = 0;
		turns = 0;
		generation = 0;
		declaredSteps = 0;
		truncatedAt = -1;
	}
}