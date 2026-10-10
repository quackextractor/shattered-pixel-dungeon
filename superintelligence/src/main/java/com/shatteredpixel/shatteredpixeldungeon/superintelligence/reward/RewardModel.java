package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
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
	private float prevHunger;

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

		int identified = countIdentified();
		if (identified > prevIdentified){
			ledger.add( RewardTerm.IDENTIFIED, 0.5f * (identified - prevIdentified), false );
		}

		//status effects
		int debuffs = countDebuffs();
		if (debuffs < prevDebuffCount){
			ledger.add( RewardTerm.CURED_DEBUFF, 1.5f * (prevDebuffCount - debuffs), false );
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
		ledger.add( RewardTerm.TURN_COST, -config.turnCost, false );
		if (tookAction) ledger.countTurn();

		double reward = ledger.flushTurn();
		snapshot();
		return reward;
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
				//-deathPenalty, i.e. exactly what dying costs.
				//
				//This reverses PLAN-reward-signals.md §3.2, which set it to zero, and the reversal is
				//deliberate rather than a correction of that analysis: §3.2 was right that a cheap
				//terminal is a degenerate strategy, and it argued for zero because "truncated, priced
				//by turn cost alone" was the honest description of what a stall then was. That
				//description stopped being true. The stall guard was firing on every descent, because
				//the environment never saw the game ask for a level transition - see
				//HeadlessGame.switchRequested - so "the episode ended on a timeout" was a harness bug
				//dressed as an outcome, and pricing it at zero made the bug free.
				//
				//Giving up and losing are now priced the same, which is the balance issues.md 9 asks
				//for. It also cannot re-create the trap §3.2 closed: stalling used to be cheaper than
				//surviving to the turn cap, and now it costs a hundred times more.
				//
				//Recorded through the same door as DEATH rather than as a separate constant, so the two
				//cannot drift apart: a config that doubles deathPenalty doubles this too, which is what
				//makes "as much as death" a property rather than a coincidence.
				ledger.add( RewardTerm.STALLED, -config.deathPenalty, false );
				break;
			case TURN_LIMIT:
				ledger.add( RewardTerm.TURN_LIMIT, -config.turnCost * 100f, false );
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
		return com.shatteredpixel.shatteredpixeldungeon.Statistics.enemiesSlain;
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