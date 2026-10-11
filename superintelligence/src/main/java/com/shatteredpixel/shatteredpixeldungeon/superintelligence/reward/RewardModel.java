package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

/**
 * Computes reward by diffing observable game state across a turn.
 *
 * research.md prescribes placing hooks directly into the game's core packages. Diffing is used
 * instead, for two reasons. First, it needs no edits to the item and buff trees, so a reward term
 * cannot silently stop firing when an item class is refactored. Second, and more importantly, a
 * diff measures what the agent's action actually *achieved* rather than what code path it took -
 * a potion that heals through a wand, a scroll that heals through an upgrade, and a kill drop all
 * produce the same measurable change.
 *
 * The trade-off is that a diff cannot see two events that cancel out within one turn. That is
 * accepted here because turns are short and the magnitudes are small relative to depth reward.
 *
 * Terms are grouped to match docs.md: positive shaping for health, depth, identification, money,
 * exploration, curing, upgrades, curse removal and alchemy; negative for early eating, damage,
 * lost money, cursed equipment and over-strength equipment.
 */
public class RewardModel {

	private final EnvConfig config;
	private final RewardLedger ledger;
	private final Curriculum curriculum;

	// --- previous-turn snapshot ---

	private int prevHp, prevMaxHp, prevLevel, prevGold, prevStrength, prevDepth, prevBranch;
	private float prevItemValue;
	private int prevIdentified;
	private int prevCursedEquipped;
	private int prevDebuffCount;
	private int prevKills;
	private int prevSneaks;
	private int prevTalentsSpent;
	private float prevHunger;
	private float prevEngineTime;

	private boolean snapshotValid = false;

	public RewardModel( EnvConfig config, RewardLedger ledger, Curriculum curriculum ){
		this.config = config;
		this.ledger = ledger;
		this.curriculum = curriculum;
	}

	/** Called on episode start and after every floor change. */
	public void resetSnapshot(){
		snapshotValid = false;
	}

	public RewardLedger ledger(){
		return ledger;
	}

	public Curriculum curriculum(){
		return curriculum;
	}

	// --------------------------------------------------------------------------- per turn

	/**
	 * Scores the turn that has just finished and returns the reward.
	 *
	 * @param newlyExplored tiles revealed this turn, from ObservationEncoder
	 * @param tookAction    whether the agent actually performed an action, as opposed to the
	 *                      environment forcing a wait
	 * @return the reward for this turn, already added to the ledger
	 */
	public double step( int newlyExplored, boolean tookAction ){
		Hero hero = Dungeon.hero;
		if (hero == null || !snapshotValid){
			snapshot();
			ledger.add( RewardTerm.TURN_COST, -config.turnCost, false );
			return ledger.flushTurn();
		}

		//progression
		int depth = Dungeon.depth;
		if (depth != prevDepth || Dungeon.branch != prevBranch){
			if (depth > prevDepth){
				//clearedByAdvancing is false: the floor this marks is the one the hero just left, which
				//SPDEnv.markFloorCleared() sets on the row that is being closed. Passing true here would
				//mark the floor the hero has just arrived on instead, and the per-floor report would say
				//every floor was cleared.
				ledger.add( RewardTerm.DEPTH_ADVANCE, config.depthReward, false );
			} else {
				//ascending is not progress toward the goal, and is not punished either: a
				//player exploring upward to loot a vault floor is playing correctly
				ledger.add( RewardTerm.DEPTH_ADVANCE, 0, false );
			}
		}

		//vitals
		if (hero.HP > prevHp){
			ledger.add( RewardTerm.MAX_HP_GAIN,
					config.maxHpReward * Math.min( hero.HP - prevHp, 10 ), false );
		} else if (hero.HP < prevHp){
			ledger.add( RewardTerm.DAMAGE_TAKEN,
					-config.damagePenalty * (prevHp - hero.HP), false );
		}

		if (hero.HT > prevMaxHp){
			ledger.add( RewardTerm.MAX_HP_GAIN,
					config.maxHpReward * 5f * (hero.HT - prevMaxHp), false );
		}

		if (hero.lvl > prevLevel){
			ledger.add( RewardTerm.LEVEL_UP, 2f * (hero.lvl - prevLevel), false );
		}

		//A talent point is worth far more than the level that granted it, and is scored separately so
		//the per-term report can say which happened.
		//
		//Levelling is automatic: Hero.earnExp raises attackSkill, defenseSkill and max HP by one each
		//and the agent had no part in it. Spending the point is a choice about which of those gains to
		//emphasise, and the options differ by an order of magnitude - LiquidAgility 2 is infinite
		//evasion, VeteranIntuition 2 identifies the armour on the spot. Paying both at 2 would say the
		//choice does not matter.
		//
		//Diffed from Hero.talentPointsSpent rather than from hasTalent, because hasTalent is true for any
		//non-zero allocation and cannot distinguish one point from two.
		//
		//Nothing can spend a point yet - Hero.upgradeTalent is only reachable through WndHero's
		//TalentsPane, which is UI - so this cannot fire in a real run until TODO.md 1.10 adds the
		//action. Wired and gated now so that the action, when it lands, has something to pay.
		int talents = talentsSpent();
		if (talents > prevTalentsSpent){
			ledger.add( RewardTerm.TALENT_UPGRADE,
					config.talentReward * (talents - prevTalentsSpent), false );
		}

		int strength = hero.STR();
		if (strength > prevStrength){
			ledger.add( RewardTerm.STRENGTH_GAIN, 1f * (strength - prevStrength), false );
		}

		//currency
		int gold = Dungeon.gold;
		if (gold > prevGold){
			ledger.add( RewardTerm.GOLD_GAIN, config.goldReward * (gold - prevGold), false );
			ledger.countGold( gold - prevGold );
		} else if (gold < prevGold){
			//docs.md punishes losing money specifically to stop shop loops
			ledger.add( RewardTerm.GOLD_LOST, -config.goldReward * 2f * (prevGold - gold), false );
		}

		//inventory
		//
		//A net diff of what the inventory is worth, in both directions.
		//
		//It was a count, compared one way: `items > prevItemCount` paid for the gain and nothing was
		//charged for the loss. `DROP` is an action the policy can take in WORLD then SLOT then WORLD,
		//and picking the same heap up again is one more INTERACT, so the loop is `INTERACT, DROP,
		//INTERACT, DROP` - unbounded, three steps per cycle, and each pickup paid out again. Nothing in
		//the reward function charged for the item leaving, so the score grew without the world changing.
		//That is not a hypothetical strategy: research.md:57 asks for exploit mitigation precisely
		//because "reinforcement learning agents are notoriously good at finding mechanical exploits",
		//and issues.md 8 reports a recording in which the hero did exactly this.
		//
		//Valuing rather than counting is also what makes the reward correct rather than merely
		//non-exploitable. Dropping a `WandOfFire` to make room for a `VelvetPouch` is a net gain and is
		//scored as one; a count cannot tell it from throwing away the only thing you were carrying.
		float carried = inventoryValue();
		if (carried > prevItemValue){
			ledger.add( RewardTerm.ITEM_PICKUP, carried - prevItemValue, false );
		} else if (carried < prevItemValue){
			ledger.add( RewardTerm.ITEM_DROPPED, carried - prevItemValue, false );
		}

		//Identification, and **this was the one term that was one-sided**: it paid on an increase and refunded
		//nothing on a decrease. A count, compared one way, so picking up an already-identified item paid
		//and dropping it cost nothing - and `DROP` then `INTERACT` is two steps the policy can repeat on
		//the same heap forever. Measured on a trainer run: `IDENTIFIED +27.50` against a pickup/drop
		//round trip that was itself **net -5.00**, so the identification payout was the entire profit and
		//the drop was not being charged for it at all.
		//
		//Same shape as the bug `ITEM_PICKUP` had before it became a two-sided value diff, and the same
		//fix: a plain signed diff, so a decrease refunds. Identified items the hero no longer carries
		//have stopped being worth knowing, and a reward term that only ever pays for additions is one the
		//agent can farm by adding.
		int identified = countIdentified();
		if (identified != prevIdentified){
			ledger.add( RewardTerm.IDENTIFIED, 0.5f * (identified - prevIdentified), false );
		}

		//status effects
		int debuffs = countDebuffs();
		if (debuffs < prevDebuffCount){
			ledger.add( RewardTerm.CURED_DEBUFF, 1.5f * (prevDebuffCount - debuffs), false );
		}

		//combat
		//
		//Both counters are the game's own. Statistics.enemiesSlain and Statistics.sneakAttacks are
		//maintained by Mob.destroy and Mob.defenseProc respectively, so reading them measures what the
		//engine decided happened rather than re-deriving it from a diff of the world.
		//
		//KILL was declared, plumbed through RewardLedger.countKill() and never emitted. Fighting was
		//therefore worth nothing while being hit cost something, and the cheapest way to clear a floor
		//was to walk past everything on it. The recorded deaths bear this out: the hero does not die in
		//a fight, he dies having been unable to leave.
		int kills = kills();
		if (kills > prevKills){
			int n = kills - prevKills;
			ledger.add( RewardTerm.KILL, config.killReward * n, false );
			ledger.countKill();
		}

		//Separate from KILL because it pays for a different decision. A kill is paid for damage dealt,
		//which is a function of the weapon and of rolls the policy cannot influence. A surprise attack
		//is paid for arriving unseen, which is a function of where the hero walked - and it is also the
		//only combat outcome that costs the hero nothing: Mob.surprisedBy skips the defender's defence
		//skill outright, so the same hit lands for far more.
		//
		//Paying the kill alone teaches trading blows with whatever is in reach. Paying this teaches
		//attacking from outside the mob's field of view, which is how a hero stays at full health while
		//still making progress - the difference between reaching floor 5 and dying on floor 1.
		int sneaks = sneaks();
		if (sneaks > prevSneaks){
			ledger.add( RewardTerm.SNEAK_ATTACK,
					config.sneakAttackReward * (sneaks - prevSneaks), false );
		}

		//equipment legality
		int cursedEquipped = countCursedEquipped();
		if (cursedEquipped > prevCursedEquipped){
			ledger.add( RewardTerm.CURSE_EQUIPPED, -5f * (cursedEquipped - prevCursedEquipped), false );
		}

		//hunger
		float hunger = hungerLevel( hero );
		if (hunger > prevHunger + 20f && !hero.isStarving()){
			//docs.md: punish eating before reaching the starving state
			ledger.add( RewardTerm.EATEN_EARLY, -1f, false );
		}

		//exploration
		if (newlyExplored > 0){
			ledger.add( RewardTerm.EXPLORE, config.exploreReward * newlyExplored, false );
		}

		//efficiency
		//
		//**Scaled by what the engine's clock says the step consumed, floored at one turn.** It was a flat
		//charge per step, and a step is not a turn: `Actor.now()` is a duration and several actions cost
		//more than one of it. `Hero.search` calls `spendAndNext(TIME_TO_SEARCH)` where `TIME_TO_SEARCH`
		//is 2, so a search bought two turns of the clock - real time in which other actors act, the hero
		//can be hit, and opportunities pass - for the price of one.
		//
		//Measured on a trainer run (`BTK-WZR-QFW`, 940 steps), reward per engine turn actually consumed:
		//
		//     SEARCH   189 steps  387 turns   -0.11 per turn
		//     MOVE_E    63 steps   73 turns   +0.10 per turn
		//
		//so 189 of 940 steps - a fifth of the episode - consumed **half the run's engine time** at half
		//price, and the agent searched the same floor over and over. Halving the cost of a step is a
		//discount; halving the cost of the two turns it actually consumes is a subsidy for the action
		//that most reliably finds nothing.
		//
		//**The floor at one turn is load-bearing, and scaling without it is a worse bug than the one this
		//fixes.** `WAIT` and `REST` cost the episode a decision without advancing `Actor.now()` at all,
		//so charging purely by the clock made idling free: 121 stalls charged 0.004 in total, which is
		//the degenerate strategy `checkStallingIsNotCheaperThanDying` exists to forbid, reached from the
		//other direction. A step that consumes the clock pays for every turn it consumed; a step that
		//consumes none still pays for one. A multi-turn action can no longer be bought at a discount,
		//and a no-clock action cannot be free.
		float turnsCharged = Math.max( 1f, engineTurnsSpent( depth ) );
		ledger.add( RewardTerm.TURN_COST, -config.turnCost * turnsCharged, false );
		if (tookAction) ledger.countTurn();

		// A step the game resolved for free.
		//
		// Actor.now() is the engine's own clock and it does not move for every action: equipping and
		// unequipping a weapon spends nothing at all, because WndUseItem.onClick calls item.execute and
		// returns without touching the hero's turn. A policy that found this could alternate the two
		// indefinitely - measured on duelist-mid, 120 consecutive USE steps at dEng=0.0 - and each one
		//cost a hundredth of a turn.
		//
		//Charged rather than refused, because the action is legal and a player may do it; what must not
		//happen is a hundred of them costing nothing. Charged from the diff and not from the stall
		//guard on purpose: the guard decides when an episode ends, and changing that would invalidate
		//every recording in the corpus. This prices the step without moving any boundary.
		//
		//Skipped on a floor change, where the clock legitimately restarts from zero - Dungeon.newLevel
		//calls Actor.clear() - and would otherwise read as a step that went backwards in time.
		if (depth == prevDepth && Actor.now() <= prevEngineTime ){
			ledger.add( RewardTerm.NO_PROGRESS, -config.noProgressPenalty, false );
		}

		double reward = ledger.flushTurn();
		snapshot();
		return reward;
	}

	/**
	 * How many engine turns this step consumed, read from the engine's own clock.
	 *
	 * <p>Zero on a floor change, where the clock legitimately restarts from zero because
	 * {@code Dungeon.newLevel} calls {@code Actor.clear()} - and a negative difference is a clock going
	 * backwards, not a turn. Both report 0, so that {@link RewardTerm#NO_PROGRESS} - which asks the same
	 * question and distinguishes "did not advance" from "went backwards" - stays the single place that
	 * decides what a free step means.
	 */
	private float engineTurnsSpent( int depthAtStepStart ){
		if (depthAtStepStart != prevDepth) return 0f;
		float spent = Actor.now() - prevEngineTime;
		return spent > 0f ? spent : 0f;
	}

	/** Records terminal reward. */
	public double terminate( TerminateReason reason ){
		Hero hero = Dungeon.hero;

		switch (reason) {
			case VICTORY:
				ledger.add( RewardTerm.VICTORY, config.victoryReward, false );
				break;
			case DEATH:
				ledger.add( RewardTerm.DEATH, -config.deathPenalty, false );
				break;
			case STALLED:
				//Worse than dying, deliberately and by an amount that is configurable rather than
				//implied. issues.md 9 asked for the two to be balanced against each other; charging
				//them equally balanced the price and lost the distinction, because to a learner the
				//two are the same number with two labels. Dying at least moved the run forward and
				//closed a floor. A stall is a run that stopped, on a hero who is alive, has not been
				//asked to do anything difficult and has not been asked to die either.
				//
				//The outcome that has to be unattractive is the one where the agent decides the run is
				//no longer worth playing. Quitting has to be worse than every alternative including
				//losing, or "stop" stays on the menu. issues.md 13 recorded what stopping currently
				//looks like - duelist-mid spending 120 steps alternating equip and unequip, which the
				//stall guard caught on position and health - and the guard is right that it is a stall;
				//what was missing was anything making the 120 steps cost.
				//
				//Recorded as its own constant rather than derived from deathPenalty, so the ordering
				//stall < death is a decision that can be retuned without arithmetic deciding it, and so
				//a config that lowers deathPenalty does not silently unbalance the other side.
				ledger.add( RewardTerm.STALLED, -config.stallPenalty, false );
				break;
			case TURN_LIMIT:
				//Running out of the floor's turn budget.
				//
				//Was -turnCost * 100, which is -0.2, against a depth reward that pays +10. A hero
				//could therefore spend 1500 turns mapping a floor, bank the exploration reward for it,
				//and be charged a fifth of a point - and since exploration pays per tile, idling out
				//the clock was a positive-value strategy rather than a failure to progress. The
				//penalty is now an explicit constant, and large enough that reaching the stairs beats
				//outwaiting them.
				ledger.add( RewardTerm.TURN_LIMIT, -config.turnLimitPenalty, false );
				break;
			default:
				break;
		}

		if (hero != null && hero.isAlive() && reason == TerminateReason.VICTORY){
			ledger.add( RewardTerm.BOSS_SLAIN, config.depthReward * 2f, false );
		}

		return ledger.flushTurn();
	}

	/**
	 * Adds a term the diff cannot see, eg. a craft or a kill the environment witnessed.
	 *
	 * <p>Goes through {@link RewardLedger#add}, which counts it. A zero amount still counts, because
	 * "this happened and it was worth nothing" is a different fact from "this did not happen", and only
	 * the first is true of a note.
	 */
	public void note( RewardTerm term, double amount ){
		ledger.add( term, amount, false );
	}

	/**
	 * Counts an event without giving it a reward.
	 *
	 * <p>For facts about a turn rather than quantities of reward. The only caller is
	 * {@code INVALID_ACTION}: an action the environment refused is worth recording and worth nothing,
	 * and routing it through {@link #note} with a zero made it invisible — it moved no total, and
	 * {@code rewardcheck} asserting on the total passed with {@code WAIT} mutated back into reporting
	 * a refusal. That is the same mistake the previous two commits made in a different place, so it is
	 * worth a separate door rather than a zero argument.
	 */
	public void noteEvent( RewardTerm term ){
		ledger.noteEvent( term );
	}

	// --------------------------------------------------------------------------- snapshot

	private void snapshot(){
		Hero hero = Dungeon.hero;
		if (hero == null) return;

		prevHp = hero.HP;
		prevMaxHp = hero.HT;
		prevLevel = hero.lvl;
		prevGold = Dungeon.gold;
		prevStrength = hero.STR();
		prevDepth = Dungeon.depth;
		prevBranch = Dungeon.branch;
		prevItemValue = inventoryValue();
		prevIdentified = countIdentified();
		prevCursedEquipped = countCursedEquipped();
		prevDebuffCount = countDebuffs();
		prevHunger = hungerLevel( hero );
		prevKills = kills();
		prevSneaks = sneaks();
		prevTalentsSpent = talentsSpent();
		prevEngineTime = Actor.now();
		snapshotValid = true;
	}

	public enum TerminateReason {
		/** Hero died. */
		DEATH,
		/** Reached and cleared the final floor. */
		VICTORY,
		/** Engine reached a state it cannot leave. */
		STALLED,
		/** Turn cap hit. */
		TURN_LIMIT,
		/** Episode reset for any other reason. */
		OTHER
	}

	// --------------------------------------------------------------------------- counters

	/**
	 * What the hero is currently carrying, in the same units {@link RewardTerm#ITEM_PICKUP} pays in.
	 *
	 * <p>A single number rather than a count, because the count was what made the pickup term
	 * one-sided. The sum is order-independent and recomputed from scratch every turn, so it cannot
	 * drift out of step with the inventory the way an incrementally maintained total could.
	 */
	private float inventoryValue(){
		float value = 0f;
		for (Item item : Dungeon.hero.belongings){
			if (item != null) value += itemValue( item );
		}
		return value;
	}

	/**
	 * docs.md: "Gaining money and collecting items (based on tier and market value, such as
	 * inventory-expanding items)".
	 *
	 * <p>Bags are worth disproportionately more because they raise the whole run's carrying capacity,
	 * so they are weighted above their gold value. An equippable scales with how far it has been
	 * upgraded, which is the part of its worth a count cannot see: a +3 wand and a fresh one occupy
	 * the same slot.
	 *
	 * <p>Flat across turns by construction - it reads {@code level()} and {@code instanceof}, never a
	 * roll - so scoring it draws no randomness, which {@code observecheck} gates for the encoders and
	 * which this would otherwise quietly break.
	 */
	private static float itemValue( Item item ){
		if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag){
			return 3f;
		}
		if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem){
			return 1f + 0.5f * ((com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem) item).level();
		}
		return 0.5f;
	}

	/**
	 * Items whose identification state is fully known.
	 *
	 * Counting identified items rather than identification events means using an unidentified
	 * potion to learn what it is earns credit, which is exactly the strategy docs.md describes.
	 */
	private int countIdentified(){
		int n = 0;
		for (Item item : Dungeon.hero.belongings){
			if (item != null && item.isIdentified()) n++;
		}
		return n;
	}

	/**
	 * Equipped items the hero is cursed by.
	 *
	 * Equipped rather than carried, because docs.md carves out an exception for "a lower-tier
	 * unequipped weapon": carrying cursed gear is a staging state, wearing it is a mistake.
	 */
	private int countCursedEquipped(){
		Belongings b = Dungeon.hero.belongings;
		int n = 0;
		Item[] worn = { b.weapon(), b.armor(), b.artifact(), b.misc(), b.ring(), b.secondWep() };
		for (Item item : worn){
			if (item != null && item.cursed && item.cursedKnown) n++;
		}
		return n;
	}

	/** Negative status effects currently active. */
	private int countDebuffs(){
		Hero hero = Dungeon.hero;
		int n = 0;
		for (Buff buff : hero.buffs()){
			if (isDebuff( buff )) n++;
		}
		return n;
	}

	private static final Class<?>[] DEBUFF_TYPES = {
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corrosion.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Doom.class,
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots.class,
	};

	private boolean isDebuff( Buff buff ){
		for (Class<?> type : DEBUFF_TYPES){
			if (type.isInstance( buff )) return true;
		}
		return false;
	}

	/** Enemies killed this run. */
	private int kills(){
		return Statistics.enemiesSlain;
	}

	/**
	 * Surprise attacks landed this run.
	 *
	 * <p>{@code Statistics.sneakAttacks} is incremented by {@code Mob.defenseProc} on exactly the
	 * condition {@code Mob.surprisedBy} reports - the hero hitting a mob that has not seen him - so it is
	 * the engine's own answer and not a re-derivation of it. It is reset by {@code Statistics.reset()},
	 * which {@code Dungeon.init} calls, so it is per-run and survives no floor boundary.
	 */
	private int sneaks(){
		return Statistics.sneakAttacks;
	}

	/**
	 * Talent points the hero has spent, summed over every tier.
	 *
	 * <p>Read through {@code Hero.talentPointsSpent(tier)} rather than by walking the map, because
	 * that is the game's own answer and it also handles the subclass and armour-ability gates.
	 *
	 * <p>Summed rather than read per tier because the tiers unlock at different levels
	 * ({@code {2, 7, 13, 21, 31}}) and diffing one tier at a time would need four snapshot fields for a
	 * quantity that only ever goes up.
	 */
	private int talentsSpent(){
		Hero hero = Dungeon.hero;
		if (hero == null) return 0;

		int total = 0;
		//talentPointsSpent indexes talents.get(tier-1), so the bound is the tier count and the
		//index is one-based.
		for (int tier = 1; tier <= hero.talents.size(); tier++){
			total += hero.talentPointsSpent( tier );
		}
		return total;
	}

	private float hungerLevel( Hero hero ){
		Hunger hunger = hero.buff( Hunger.class );
		return hunger == null ? 0f : hunger.hunger();
	}

	/** True when the item is something docs.md wants the agent to craft. */
	public static boolean isCraftable( Item item ){
		return item instanceof Potion
				|| item instanceof Scroll
				|| item instanceof Food;
	}

	/** True when the item would raise a stat, which docs.md rewards. */
	public static boolean isUpgrade( Item item ){
		return item instanceof EquipableItem;
	}
}