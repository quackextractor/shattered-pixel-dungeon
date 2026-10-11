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
	public float turnCost = 1f;

	/**
	 * Per-floor-advance reward, the primary goal. Survives the sparse phase.
	 *
	 * <p>Deliberately the largest single-turn-or-later payout in the function. A regular floor holds
	 * several hundred walkable tiles, so at {@code exploreReward} per tile a hero that maps a floor
	 * without ever taking the stairs collects more than descending pays - which is precisely the
	 * behaviour every run so far has shown, and why {@code bestDepth} has been 1 throughout. The
	 * ratio between this and {@code exploreReward} <em>is</em> the objective, and it is the number to
	 * change first when depth does not move.
	 */
	public float depthReward = 1800f;

	/** Reward for exploring a never-before-seen tile, faded out by the curriculum. */
	public float exploreReward = 0.01f;

	/** Reward per point of maximum HP gained. */
	public float maxHpReward = 0.5f;

	/**
	 * Reward for spending a talent point.
	 *
	 * <p><b>Deliberately close to a floor's worth.</b> Levelling itself pays {@code 2} per level, and
	 * that is correct — a level gives +1 attack, +1 defence and a little max HP, and it happens whether
	 * the agent plays well or not. A talent point is a different thing: it is a permanent choice about
	 * <i>which</i> gains to take, and the choices are not remotely equal. {@code LiquidAgility} 2 is
	 * infinite evasion. {@code VeteranIntuition} 2 identifies the hero's armour the moment it is taken.
	 * {@code GrowingPower} 1 is +75% speed. Paying that the same as a level would say the choice does
	 * not matter, which is the opposite of what the game is built on.
	 *
	 * <p>Sized at 20 — most of a floor's 25 — so that spending a point clearly beats another turn of
	 * the same floor, which is what makes it worth an action the agent would otherwise not have a
	 * reason to take. Not the largest term here: a descent is still the objective, and a talent is
	 * worthless on a floor the agent never survives.
	 *
	 * <p><b>Nothing can spend a point yet.</b> It is UI-only in the game — {@code TalentButton} ->
	 * {@code TalentsPane} -> {@code WndHero} — and none of that exists headlessly. The emission site is
	 * wired and gated; the action that reaches it is {@code TODO.md} 1.10.
	 */
	public float talentReward = 7000f;
	/** Reward per point of gold gained, scaled by the item's tier. */
	public float goldReward = 0.05f;

/**
	 * Penalty per point of health lost.
	 *
	 * <p><b>Sized against {@link #deathPenalty} rather than picked.</b> A floor-1 hero has 20 HP, and
	 * {@code damagePenalty * 20} is what losing a full health bar costs. At 0.25 that was 5 against a
	 * death penalty of 100 — five per cent — and a run that died 100% of its episodes had <i>no gradient
	 * at all</i>: every episode returned the same terminal, and the only signal that varied was a term
	 * worth a twentieth of it. The policy could not have learned from that, and two runs confirmed it.
	 *
	 * <p>At 2.5 a full floor-1 health bar costs half a death, so the agent feels every hit on the way
	 * down rather than only the ending — which is what makes the terminal informative, since dying is
	 * then the limit of a signal already present for most of the episode rather than a cliff the policy
	 * sees once.
	 *
	 * <p><b>Why not more.</b> A linear per-HP penalty is depth-dependent by construction, because the
	 * hero's bar grows: at 5.0 a floor-1 bar costs one death and a floor-5 bar (~60 HP) costs three. That
	 * is a real weakness of this number and it is recorded rather than designed away — making both
	 * sides scale with depth is the fix and is a `RewardModel` change, not a config change.
	 *
	 * <p>The relationship, not the number, is the property: {@code damagePenalty * heroMaxHP} should be
	 * within an order of magnitude of {@code deathPenalty}, and {@code killReward} should cover the
	 * damage a fight costs. Halving one without the others unbalances it again, which is what happened
	 * the first time this was raised: at 5.0 with {@code killReward} still 3.0, killing a rat cost 40 and
	 * paid 3, and fighting became a net -37.
	 */
	public float damagePenalty = 50f;

	/**
	 * Reward for killing a hostile mob.
	 *
	 * <p>{@code RewardTerm.KILL} and {@code RewardLedger.countKill()} have existed since the term was
	 * declared and neither was ever called, so fighting something was worth exactly nothing while
	 * taking a hit cost something. A policy under that price learns to walk past everything, which is
	 * the shape every death in the corpus has: the hero does not die in a fight, he dies having run
	 * out of room. See {@code TODO.md} 2.2.
	 *
	 * <p>Sized against {@link #damagePenalty}: a floor-1 rat is about 8 HP and a melee kill trades
	 * roughly half that, so a melee kill is close to break-even and the profitable kill is the one that
	 * costs nothing — which is what {@link #sneakAttackReward} is for.
	 */
	public float killReward = 500f;

	/**
	 * Extra reward for a surprise attack.
	 *
	 * <p>Read from {@code Statistics.sneakAttacks`, the game's own counter, incremented in
	 * {@code Mob.defenseProc} when the hero hits a mob that has not seen him. It is worth paying well
	 * above the kill because it is the one combat outcome decided by <i>approach</i> rather than by
	 * damage rolls: {@code Mob.surprisedBy} skips the mob's defence skill entirely, so the same hit
	 * takes far more off the same health pool, and — unlike a melee kill — <b>it costs the hero
	 * nothing</b>.
	 *
	 * <p>That is the whole design. Fighting has to be worth doing, and the only way it is worth doing is
	 * to not be hit; a reward structure that pays the same for both outcomes teaches trading blows.
	 */
	public float sneakAttackReward = 900f;

	/**
	 * Penalty for a step that advanced no engine time at all.
	 *
	 * <p>A step is not a turn, and the difference is load-bearing. Equipping and unequipping a weapon
	 * costs zero - {@code WndUseItem.onClick} calls {@code item.execute} and returns without spending
	 * anything - so {@code USE} on an equippable is a free action in the real game and the policy found
	 * it, alternating for 120 steps on one {@code Rapier}. The stall guard eventually catches it on
	 * position and health, which it should, but a hundred and twenty free steps in between are paid at
	 * the ordinary turn rate and so cost almost nothing.
	 *
	 * <p>Charging here rather than tightening the guard is deliberate: it prices the action honestly
	 * without changing when any recording ends, so the corpus stays valid. See {@code TODO.md} 0.10.
	 */
	public float noProgressPenalty = 20f;

	/** Terminal penalty for dying. Large: dying should dominate any accumulated micro-reward. */
	public float deathPenalty = 2000f;

	/**
	 * Terminal penalty for giving up.
	 *
	 * <p>Deliberately worse than {@link #deathPenalty}. Dying at least moved the run forward and
	 * cleared the floor; a stall is a run that decided to stop, on a hero that is alive and has not
	 * been asked to do anything difficult. Charging the two the same made them indistinguishable to
	 * the learner, and the lesson that survives is that quitting is survivable.
	 */
	public float stallPenalty = 2500f;

	/**
	 * Terminal penalty for running out of a floor's turn budget.
	 *
	 * <p>Was {@code -turnCost * 100}, which is -0.2 against a +10 depth reward: a hero could map an
	 * entire floor for 1500 turns, bank the exploration, and be charged a fifth of a point for it.
	 * With exploration paying per tile, idling out the clock <i>was</i> the strategy. At these numbers
	 * the two outcomes are ordered the way they are meant to be - stall, then turn limit, then death,
	 * then victory.
	 */
	public float turnLimitPenalty = 1600f;

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
		c.talentReward = talentReward;
		c.goldReward = goldReward;
		c.damagePenalty = damagePenalty;
		c.killReward = killReward;
		c.sneakAttackReward = sneakAttackReward;
		c.noProgressPenalty = noProgressPenalty;
		c.deathPenalty = deathPenalty;
		c.stallPenalty = stallPenalty;
		c.turnLimitPenalty = turnLimitPenalty;
		c.victoryReward = victoryReward;
		c.persistSaves = persistSaves;
		return c;
	}
}
