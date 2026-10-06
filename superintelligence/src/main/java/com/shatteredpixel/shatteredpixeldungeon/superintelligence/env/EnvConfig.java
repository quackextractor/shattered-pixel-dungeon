package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

/**
 * Every tunable the environment exposes, in one place.
 *
 * Kept as a plain config object rather than scattered constants so a rollout can be reproduced
 * from a saved config, and so curriculum phases and reward weights stay inspectable.
 */
public class EnvConfig {

	// --- action space geometry ---

	/** Inventory slots the slot head covers. Backpack capacity is 20 plus one per bag. */
	public int maxSlots = 32;

	/** Board size fed to the CNN. Covers the widest floors plus the hero's 8-tile view radius. */
	public int gridWidth = 48;
	public int gridHeight = 48;

	/**
	 * Spatial planes in the observation.
	 *
	 * Derived from the channel list rather than configured, so adding a channel to
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel} cannot
	 * silently desynchronise the encoder from the network's input shape.
	 */
	public int spatialChannels(){
		return com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel.spatialCount();
	}

	// --- episode limits ---

	/**
	 * Hard cap on hero turns per floor.
	 *
	 * research.md: "To prevent infinite stalling, you must plan a hard cap on the number of turns
	 * allowed per floor". Without it an agent that farms Regeneration or paces in a shop never
	 * terminates, and the run score becomes meaningless.
	 */
	public int turnLimitPerFloor = 1500;

	/** Whole-episode turn cap, across all floors. Backstop for a policy that keeps descending. */
	public int turnLimitTotal = 40000;

	/**
	 * Actor steps allowed before yielding control to the agent.
	 *
	 * Reached only when the engine is waiting on something the agent cannot influence, eg. a
	 * chain of buffs on a mob. Small, because a legitimate turn never needs many actors.
	 */
	public int actorStepLimit = 20000;

	/** Consecutive turns with no change to HP, depth, position or inventory before giving up. */
	public int stallLimit = 120;

	// --- vision ---

	/** Clamp on hero.viewDistance when building the observation grid. */
	public int maxViewDistance = 12;

	/** Vision buffs (Light, Magical Sight) are honoured by default, as they are for a human. */
	public boolean honourVisionBuffs = true;

	/**
	 * Mimics stay visible outside the field of view once seen.
	 *
	 * docs.md: "it will always be able to spot regular mimics since they animate even when the
	 * player is idle". The game models this with GameScene.afterObserve setting
	 * {@code visibleOutOfFFOV}, so replicating it here keeps the agent's information set equal to
	 * a player's rather than strictly smaller.
	 */
	public boolean mimicsVisibleOutOfFog = true;

	// --- items ---

	/** Allow the agent to equip and unequip from the inventory pane. */
	public boolean allowEquipping = true;

	/** Allow the agent to open shop and blacksmith dialogs. */
	public boolean allowTrading = true;

	/** Allow the agent to use the alchemy pot. */
	public boolean allowAlchemy = true;

	// --- reward shaping, see reward.RewardWeights ---

	/** Curriculum stage 0..1. Drives the dense-to-sparse fade described in research.md. */
	public float curriculumProgress = 0f;

	/** Constant per-turn penalty, in reward per turn. Speeds the agent up. */
	public float turnCost = 0.002f;

	/** Per-floor-advance reward, the primary goal. Survives the sparse phase. */
	public float depthReward = 10f;

	/** Reward for exploring a never-before-seen tile, faded out by the curriculum. */
	public float exploreReward = 0.05f;

	/** Reward per point of maximum HP gained. */
	public float maxHpReward = 0.5f;

	/** Reward per point of gold gained, scaled by the item's tier. */
	public float goldReward = 0.05f;

	/** Penalty per point of health lost. */
	public float damagePenalty = 0.1f;

	/** Terminal penalty for dying. Large: dying should dominate any accumulated micro-reward. */
	public float deathPenalty = 100f;

	/** Terminal reward for reaching and clearing the final floor. */
	public float victoryReward = 1000f;

	/** Set false to have every write to Gdx.files redirected into a scratch directory. */
	public boolean persistSaves = false;

	public EnvConfig copy(){
		EnvConfig c = new EnvConfig();
		c.maxSlots = maxSlots;
		c.gridWidth = gridWidth;
		c.gridHeight = gridHeight;
		c.turnLimitPerFloor = turnLimitPerFloor;
		c.turnLimitTotal = turnLimitTotal;
		c.actorStepLimit = actorStepLimit;
		c.stallLimit = stallLimit;
		c.maxViewDistance = maxViewDistance;
		c.honourVisionBuffs = honourVisionBuffs;
		c.mimicsVisibleOutOfFog = mimicsVisibleOutOfFog;
		c.allowEquipping = allowEquipping;
		c.allowTrading = allowTrading;
		c.allowAlchemy = allowAlchemy;
		c.curriculumProgress = curriculumProgress;
		c.turnCost = turnCost;
		c.depthReward = depthReward;
		c.exploreReward = exploreReward;
		c.maxHpReward = maxHpReward;
		c.goldReward = goldReward;
		c.damagePenalty = damagePenalty;
		c.deathPenalty = deathPenalty;
		c.victoryReward = victoryReward;
		c.persistSaves = persistSaves;
		return c;
	}
}