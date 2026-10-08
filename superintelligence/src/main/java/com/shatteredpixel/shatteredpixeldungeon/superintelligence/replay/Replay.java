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
	//2 adds max_slots and allow_equipping. Version 1 recorded neither, so a v1 replay cannot say
	//how wide the slot head was or whether equipping was permitted, and a replay that does not record
	//what changed the meaning of a slot index cannot be trusted to resolve one the same way twice.
	//v1 still parses, with EnvConfig's defaults, so the tooling fixtures recorded before this stay
	//readable - but they were recorded against the RNG stream that has since been repaired and so
	//reproduce nothing anyway.
	public static final int VERSION = 2;

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
	 * How many inventory slots the recorded slot indices were chosen against.
	 *
	 * Recorded because a slot index means nothing without it: the head is a fixed-width window over
	 * the inventory, so the same index names a different item at a different width. These are the two
	 * settings {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper}
	 * reads, and between them they are everything a replay needs to resolve an index the way the
	 * recorder did.
	 */
	public int maxSlots = 32;

	/**
	 * Whether equipping was permitted when the run was recorded.
	 *
	 * Not cosmetic: {@code SlotAction.use} consults it before deciding whether to equip an
	 * {@code EquipableItem}, so the same USE either toggles a stat or arms an aim depending on it. A
	 * replay that guessed wrong here would diverge at the first weapon change.
	 */
	public boolean allowEquipping = true;

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

		/**
		 * Hero HP after the step, or -1 when not recorded.
		 *
		 * <p>Recorded because position alone is not enough to tell two runs apart. A hero who is starving
		 * stands exactly where a fed hero stands, so a recording that stored only {@link #heroPos} could
		 * not detect an entire class of divergence - including the headless run whose hunger clock was
		 * frozen by the intro setting, which played back perfectly while being wrong from step 454 on.
		 */
		public int heroHp = -1;

		/**
		 * Engine time after the step, or a negative value when not recorded.
		 *
		 * <p>A turn is a duration, not a count: a heavy weapon costs two, haste under one, so the
		 * counter is fractional. Comparing it is what caught a step that spent a turn the recording said
		 * it did not - the two environments were one turn apart from step 2 onwards and identical in
		 * position throughout.
		 */
		public float turn = -1f;

		/** Inventory contents after the step, as {@code ItemClass:count} pairs. Empty when not recorded. */
		public String inventory = "";

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