package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

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
	public static final int VERSION = 3;

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
	 * Which reward terms were allowed to pay while this run was recorded.
	 *
	 * Not trajectory: the same actions under a different profile carry different rewards, so a recording
	 * verified under the wrong one reports a score and a reward column that never matched and says
	 * nothing about the run. Absent from every recording written before the profile existed, which is
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile#DEFAULT} -
	 * what those runs were priced with.
	 */
	public com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile rewardProfile
			= com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;

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

	/**
	 * Whether the recording was written by a wire that dropped step fields.
	 *
	 * <p><b>This, not the absence of quickslots, is what says a recording cannot be watched.</b>
	 *
	 * <p>The obvious discriminator is wrong, and it took a flaky `rolloutcheck` to show it. "No step
	 * carries bindings" reads as "the field was lost" — and it is equally true of a run in which the hero
	 * genuinely had nothing bound at every slot step, which resolves perfectly from backpack order and
	 * plays. Refusing those refuses a working recording, and it fails intermittently because it depends
	 * on what a given random episode happened to be carrying.
	 *
	 * <p>The evidence is on the same frame. The broken wire sent four of nine step fields and left the
	 * other five at their *not recorded* sentinels — so health, engine time and inventory are absent too,
	 * on every step. Nine trainer recordings carry {@code heroHp = -1, turn = -1.0, inventory = ""}
	 * throughout, and that signature is what identifies them; a recording with real health and real engine
	 * time was written by a wire that carried everything, so an empty binding on it means the hero had
	 * none.
	 *
	 * <p>Presence of evidence rather than absence of it, which is the lesson {@code issues.md} 15 exists
	 * to record.
	 */
	public boolean wireDroppedStepFields(){
		if (steps.isEmpty()) return stepFieldsDropped;
		for (Step step : steps){
			if (step.heroHp >= 0 || step.turn >= 0 || !step.inventory.isEmpty()) return false;
		}
		return true;
	}

	/**
	 * The same answer, for a recording read header-only.
	 *
	 * <p>True by default, because only a body scan can establish otherwise.
	 * {@link ReplayIO#readHeader} sets it.
	 */
	public boolean stepFieldsDropped = false;

	/**
	 * Whether any step that names a slot carries the quickslot bindings it was chosen against.
	 *
	 * <p>Diagnostic rather than a gate: it says the hero had nothing bound at that moment, which is a
	 * state and not a fault. What decides whether a recording can be watched is
	 * {@link #wireDroppedStepFields()}.
	 */
	public boolean recordsQuickslots(){
		if (steps.isEmpty()) return quickslotsRecorded;
		for (Step step : steps){
			if (!EnvMode.SLOT.name().equals( step.mode ) && !EnvMode.INVENTORY.name().equals( step.mode )){
				continue;
			}
			if (step.quickslots != null && !step.quickslots.isEmpty()) return true;
		}
		return false;
	}

	/** The same answer, for a recording read header-only. See {@link #stepFieldsDropped}. */
	public boolean quickslotsRecorded = true;

	/** Total score, kept in sync with the last recorded reward. */
/**
	 * Which training run wrote this, as an id the trainer was given, or empty when it was not grouped.
	 *
	 * <p>Metadata rather than trajectory: it changes no step, no seed and no setting, so playback and
	 * verification do not consult it and its absence on an older recording is not a fault. That is why it
	 * does not bump {@link #VERSION} - every header key is read by name, and the answer for a missing one
	 * is already defined (no group), unlike the fields that do change what a recording meant.
	 *
	 * <p>The catalogue lists one section per group, so a run can be watched against the run it was
	 * compared with rather than against every other run ever trained.
	 */
	public String group = "";

	/**
	 * Why the run ended, as a {@code RewardModel.TerminateReason} name, or empty when unknown.
	 *
	 * <p>Recorded because the viewer cannot otherwise tell a complete recording from a truncated one. A
	 * run that ended because it ran out of turns, or because the stall guard saw nothing change, leaves
	 * a healthy hero that will never become ready - and a viewer with no environment to ask waits for one
	 * forever, then reports a stall that is false. The recording was not truncated; it was finished.
	 *
	 * <p>Empty in versions 1 and 2, which predate the field. Such a recording is still played to its last
	 * step; it just cannot be recognised as complete.
	 */
	public String termination = "";

	/** Closes the recording and stores the run's outcome, including why it ended. */
	public void captureOutcome( double score, int depth, int turns, int generation, String termination ){
		this.score = score;
		this.depth = depth;
		this.turns = turns;
		this.generation = generation;
		this.termination = termination == null ? "" : termination;
	}

	public void reset(){
		steps.clear();
		score = 0;
		depth = 0;
		turns = 0;
		generation = 0;
		declaredSteps = 0;
		truncatedAt = -1;
		rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;
	}
}