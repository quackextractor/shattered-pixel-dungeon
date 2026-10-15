package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Every tunable the environment exposes, in one place.
 *
 * Kept as a plain config object rather than scattered constants so a rollout can be reproduced
 * from a saved config, and so curriculum phases and reward weights stay inspectable.
 */
public class EnvConfig {



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

	/**
	 * Steps on one floor that may advance no engine time at all before the episode is called stalled.
	 *
	 * <p>A second stall guard, and it exists because {@code stallLimit} counts consecutive steps where the
	 * hero's cell and health are unchanged, so <b>one move resets it</b> - which is exactly what a
	 * free-action loop does. Measured on a trainer recording, {@code HRT-YYP-KGC}: 502 of 1215 steps
	 * advanced no engine time, 41% of the episode's whole cost, almost all of it {@code OPEN_INVENTORY}
	 * followed by {@code CANCEL} out of {@code INVENTORY}, with a real move between pairs. The position
	 * guard never reached a hundred.
	 *
	 * <p>A total on the floor rather than a streak, because a streak is cleared by the very move the loop
	 * makes between its pairs.
	 *
	 * <p><b>250, and it was 40.</b> 40 was chosen as "about twenty honest equip/unequip cycles" and it was
	 * wrong by a wide margin, because the budget is charged for far more than equipping: measured over
	 * 4,828 steps in 40 uniform-policy episodes, about <b>570 advanced the clock - roughly 10%</b> of all
	 * steps. {@code CANCEL} and {@code OPEN_INVENTORY} are free 100% of the time, {@code REST} 99.1%,
	 * {@code USE} 88% (212 of its 401 free steps being the mandatory {@code WORLD}->{@code SLOT}
	 * hand-off), {@code DROP} 50%. Moves are the only action that reliably advances time.
	 *
	 * <p>At 40 the arithmetic is fatal. A policy that opens its inventory once every ten turns spends its
	 * whole budget by turn ~120 against {@code turnLimitPerFloor} of 1500, so the episode ended at
	 * <b>8% of the turns it was granted</b> and floor 1's stairs were arithmetically out of reach. Across
	 * 5,360 episodes {@code meanDepth} was 1.001: {@code depthReward} of +1800, the largest term in the
	 * function, was paid in roughly five of them. See {@code issues.md} 27 and 29.
	 *
	 * <p>Measured after the change, 10 generations, same config and seed, only this value differing:
	 *
	 * <pre>
	 *   budget 40    meanTurns ~122   stalled 98.9%   bestDepth&gt;=2 in  2 of 67 generations
	 *   budget 250   meanTurns ~670   stalled 79.2%   bestDepth&gt;=2 in  8 of 10 generations
	 * </pre>
	 *
	 * <p>8 of 10 is the regime {@code TODO.md` 1.15 recorded before this guard existed - 104 of 150 - so
	 * this restores behaviour that was lost, rather than introducing a new one. The price is that
	 * {@code meanScore} falls, because longer episodes accumulate more turn cost and more per-step free
	 * charges; at 250 it sits near -3,890 against -3,115 at 40. That is a longer path to the same
	 * penalties, and it is the trade the depth numbers are paying for.
	 *
	 * <p>Priced rather than prevented, as {@link #noProgressPenalty} is: the actions are legal and a
	 * player may do them, so what must not happen is unbounded numbers of them. {@link #stallLimit} is
	 * still behind it for the case that moves freely but achieves nothing, so raising this does not leave
	 * the free-action loop unbounded.
	 */
	public int freeStepBudget = 250;



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



	/** Allow the agent to equip and unequip from the inventory pane. */
	public boolean allowEquipping = true;

	/** Allow the agent to open shop and blacksmith dialogs. */
	public boolean allowTrading = true;

	/** Allow the agent to use the alchemy pot. */
	public boolean allowAlchemy = true;



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
	public float exploreReward = 0.05f;

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
	public float talentReward = 400f;
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
	public float damagePenalty = 40f;

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
	public float killReward = 150f;

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
	public float sneakAttackReward = 300f;

	/**
	 * What a step that advanced no engine time costs, as a fraction of what a real turn costs.
	 *
	 * <p>Was an absolute penalty ({@code noProgressPenalty}, 12.0 against a turn cost of 1.0), and the
	 * shape of that was wrong twice over. It made a free step cost thirteen ordinary steps, so a dead-end
	 * episode paid 120 x 12 = 1,440 in per-step charges <em>before</em> the stall terminal - charging the
	 * same behaviour twice and drowning out the ordering the terminal exists to express. And it was a
	 * second number where the question is a ratio.
	 *
	 * <p>0.5, so a free step costs half a turn: priced rather than free, because equipping and
	 * unequipping cost nothing in the real game too and a legal action may be done a hundred times.
	 *
	 * <p><b>Cheaper than a turn is a subsidy, and this is deliberately exposed rather than tuned away.</b>
	 * The cheapest action available to a policy is now the free one, and the thing that stops it being
	 * farmed is {@link #freeStepBudget} rather than the price. Raise this toward 1.0 if a run starts
	 * preferring free steps to real ones; that is what the number is for.
	 */
	public float freeStepCostFactor = 0.5f;

	/** Terminal penalty for dying. Large: dying should dominate any accumulated micro-reward. */
	public float deathPenalty = 2500f;

	/**
	 * Terminal penalty for giving up.
	 *
	 * <p>Deliberately worse than {@link #deathPenalty}. Dying at least moved the run forward and
	 * cleared the floor; a stall is a run that decided to stop, on a hero that is alive and has not
	 * been asked to do anything difficult. Charging the two the same made them indistinguishable to
	 * the learner, and the lesson that survives is that quitting is survivable.
	 */
	public float stallPenalty = 3000f;

	/**
	 * Terminal penalty for running out of a floor's turn budget.
	 *
	 * <p>Was {@code -turnCost * 100}, which is -0.2 against a +10 depth reward: a hero could map an
	 * entire floor for 1500 turns, bank the exploration, and be charged a fifth of a point for it.
	 * With exploration paying per tile, idling out the clock <i>was</i> the strategy. At these numbers
	 * the two outcomes are ordered the way they are meant to be - stall, then turn limit, then death,
	 * then victory.
	 */
	public float turnLimitPenalty = 2000f;

	/** Terminal reward for reaching and clearing the final floor. */
	public float victoryReward = 60000f;

	/**
	 * Which terms are allowed to pay. DEFAULT is every term; DEPTH_ONLY leaves reaching a new floor or
	 * winning as the only route to a positive score.
	 *
	 * <p>A temporary experimental override rather than a re-pricing: every weight above still applies,
	 * and {@code reward.profile=default} restores all of them. It exists to measure what the objective
	 * alone is worth, which cannot be read off a function that also pays for walking over new tiles.
	 */
	public com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile rewardProfile
			= com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;

	/** Set false to have every write to Gdx.files redirected into a scratch directory. */
	public boolean persistSaves = false;

	/**
	 * Writes every field this class owns, for shipping the configuration to a worker process.
	 *
	 * <p>This exists because two hand-maintained lists - {@code Trainer.writeParams} and
	 * {@code Worker.readParams} - were the only places the trainer and its workers agreed on what a
	 * configuration is, and they agreed on ten fields out of thirty. The twenty they dropped were
	 * dropped *silently*: the worker built its own {@code EnvConfig} and kept its own defaults, so a
	 * {@code .env} override of {@code damagePenalty} or {@code freeStepBudget} was applied to the
	 * trainer, reported as applied, and had no effect on the process that actually runs the episodes.
	 *
	 * <p>One list, here, read by both sides, is the fix that does not need remembering. Every field
	 * {@link #copy()} copies is written, and {@code ConfigCheck} asserts the two are the same set.
	 */
	public void writeTo( DataOutputStream out ) throws IOException {
		out.writeInt( maxSlots );
		out.writeInt( gridWidth );
		out.writeInt( gridHeight );
		out.writeInt( turnLimitPerFloor );
		out.writeInt( turnLimitTotal );
		out.writeInt( actorStepLimit );
		out.writeInt( stallLimit );
		out.writeInt( freeStepBudget );
				out.writeInt( maxViewDistance );
		out.writeFloat( curriculumProgress );
		out.writeFloat( turnCost );
		out.writeFloat( depthReward );
		out.writeFloat( exploreReward );
		out.writeFloat( maxHpReward );
		out.writeFloat( talentReward );
		out.writeFloat( goldReward );
		out.writeFloat( damagePenalty );
		out.writeFloat( killReward );
		out.writeFloat( sneakAttackReward );
		out.writeFloat( freeStepCostFactor );
		out.writeFloat( deathPenalty );
		out.writeFloat( stallPenalty );
		out.writeFloat( turnLimitPenalty );
		out.writeFloat( victoryReward );
		out.writeBoolean( honourVisionBuffs );
		out.writeBoolean( mimicsVisibleOutOfFog );
		out.writeBoolean( allowEquipping );
		out.writeBoolean( allowTrading );
		out.writeBoolean( allowAlchemy );
		out.writeBoolean( persistSaves );


		out.writeUTF( rewardProfile.key() );
	}

	/** Reads every field {@link #writeTo} wrote. The mirror of it, and the only other list. */
	public void readFrom( DataInputStream in ) throws IOException {
		maxSlots = in.readInt();
		gridWidth = in.readInt();
		gridHeight = in.readInt();
		turnLimitPerFloor = in.readInt();
		turnLimitTotal = in.readInt();
		actorStepLimit = in.readInt();
		stallLimit = in.readInt();
		freeStepBudget = in.readInt();
		maxViewDistance = in.readInt();
		curriculumProgress = in.readFloat();
		turnCost = in.readFloat();
		depthReward = in.readFloat();
		exploreReward = in.readFloat();
		maxHpReward = in.readFloat();
		talentReward = in.readFloat();
		goldReward = in.readFloat();
		damagePenalty = in.readFloat();
		killReward = in.readFloat();
		sneakAttackReward = in.readFloat();
		freeStepCostFactor = in.readFloat();
		deathPenalty = in.readFloat();
		stallPenalty = in.readFloat();
		turnLimitPenalty = in.readFloat();
		victoryReward = in.readFloat();
		honourVisionBuffs = in.readBoolean();
		mimicsVisibleOutOfFog = in.readBoolean();
		allowEquipping = in.readBoolean();
		allowTrading = in.readBoolean();
		allowAlchemy = in.readBoolean();
		persistSaves = in.readBoolean();
		rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
				.parse( in.readUTF() );
	}

	public EnvConfig copy(){
		EnvConfig c = new EnvConfig();
		c.maxSlots = maxSlots;
		c.gridWidth = gridWidth;
		c.gridHeight = gridHeight;
		c.turnLimitPerFloor = turnLimitPerFloor;
		c.turnLimitTotal = turnLimitTotal;
		c.actorStepLimit = actorStepLimit;
		c.stallLimit = stallLimit;
		c.freeStepBudget = freeStepBudget;
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
		c.freeStepCostFactor = freeStepCostFactor;
		c.deathPenalty = deathPenalty;
		c.stallPenalty = stallPenalty;
		c.turnLimitPenalty = turnLimitPenalty;
		c.victoryReward = victoryReward;
		c.rewardProfile = rewardProfile;
		c.persistSaves = persistSaves;
		return c;
	}
}
