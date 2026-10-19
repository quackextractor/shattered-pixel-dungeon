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
public class RewardModel {
	private final EnvConfig config;
	private final RewardLedger ledger;
	private final Curriculum curriculum;
	private int prevHp, prevMaxHp, prevLevel, prevGold, prevStrength, prevDepth, prevBranch;
	private float prevItemValue;
	private int prevIdentified;
	private int prevCursedEquipped;
	private int prevCarried;
	private int prevCursedCarried;
	private int prevEquippedOverStrength;
	private int prevDebuffCount;
	private int prevKills;
	private int prevCrafted;
	private int prevSneaks;
	private int prevTalentsSpent;
	private float prevHunger;
	private float prevEngineTime;
	private boolean snapshotValid = false;
	private int deepestPaid = 0;
	private boolean lastStepAdvancedClock = true;
	public RewardModel( EnvConfig config, RewardLedger ledger, Curriculum curriculum ){
		this.config = config;
		this.ledger = ledger;
		this.curriculum = curriculum;
	}
	public void resetSnapshot(){
		snapshotValid = false;
		deepestPaid = 0;
	}
	public RewardLedger ledger(){
		return ledger;
	}
	public Curriculum curriculum(){
		return curriculum;
	}
	public boolean lastStepAdvancedClock(){
		return lastStepAdvancedClock;
	}
	public double step( int newlyExplored, boolean tookAction ){
		return step( newlyExplored, tookAction, false );
	}
	public double step( int newlyExplored, boolean tookAction, boolean menuNoop ){
		Hero hero = Dungeon.hero;
		if (hero == null || !snapshotValid){
			snapshot();
			ledger.add( RewardTerm.TURN_COST, -config.turnCost, false );
			return ledger.flushTurn();
		}
		int depth = Dungeon.depth;
		if (depth != prevDepth || Dungeon.branch != prevBranch){
			if (depth > prevDepth && depth > deepestPaid){
				deepestPaid = depth;
				ledger.add( RewardTerm.DEPTH_ADVANCE, config.depthReward, false );
			} else {
				ledger.add( RewardTerm.DEPTH_ADVANCE, 0, false );
			}
		}
		if (hero.HP > prevHp){
			ledger.add( RewardTerm.MAX_HP_GAIN,
					config.maxHpReward * Math.min( hero.HP - prevHp, config.maxHpGainCap ), false );
		} else if (hero.HP < prevHp){
			ledger.add( RewardTerm.DAMAGE_TAKEN,
					-config.damagePenalty * (prevHp - hero.HP), false );
		}
		if (hero.HT > prevMaxHp){
			ledger.add( RewardTerm.MAX_HP_GAIN,
					config.maxHpReward * 5f * (hero.HT - prevMaxHp), false );
		}
		if (hero.lvl > prevLevel){
			ledger.add( RewardTerm.LEVEL_UP, config.levelUpReward * (hero.lvl - prevLevel), false );
		}
		int talents = talentsSpent();
		if (talents > prevTalentsSpent){
			ledger.add( RewardTerm.TALENT_UPGRADE,
					config.talentReward * (talents - prevTalentsSpent), false );
		}
		int strength = hero.STR();
		if (strength > prevStrength){
			ledger.add( RewardTerm.STRENGTH_GAIN, config.strengthGainReward * (strength - prevStrength), false );
		}
		int gold = Dungeon.gold;
		if (gold > prevGold){
			ledger.add( RewardTerm.GOLD_GAIN, config.goldReward * (gold - prevGold), false );
			ledger.countGold( gold - prevGold );
		} else if (gold < prevGold){
			ledger.add( RewardTerm.GOLD_LOST, -config.goldReward * config.goldLostFactor * (prevGold - gold), false );
		}
		float carried = inventoryValue();
		if (carried > prevItemValue){
			ledger.add( RewardTerm.ITEM_PICKUP, carried - prevItemValue, false );
		} else if (carried < prevItemValue){
			ledger.add( RewardTerm.ITEM_DROPPED, carried - prevItemValue, false );
		}
		int identified = countIdentified();
		if (identified != prevIdentified){
			ledger.add( RewardTerm.IDENTIFIED, config.identifiedReward * (identified - prevIdentified), false );
		}
		int debuffs = countDebuffs();
		if (debuffs < prevDebuffCount){
			ledger.add( RewardTerm.CURED_DEBUFF, config.curedDebuffReward * (prevDebuffCount - debuffs), false );
		}
		int kills = kills();
		if (kills > prevKills){
			int n = kills - prevKills;
			ledger.add( RewardTerm.KILL, config.killReward * n, false );
			ledger.countKill();
		}
		int sneaks = sneaks();
		if (sneaks > prevSneaks){
			ledger.add( RewardTerm.SNEAK_ATTACK,
					config.sneakAttackReward * (sneaks - prevSneaks), false );
		}
		int cursedEquipped = countCursedEquipped();
		if (cursedEquipped > prevCursedEquipped){
			ledger.add( RewardTerm.CURSE_EQUIPPED, config.curseEquippedPenalty * (cursedEquipped - prevCursedEquipped), false );
		}
		int stillCarried = countCarried();
		int cursedCarried = countCursedCarried();
		if (cursedCarried < prevCursedCarried && stillCarried == prevCarried){
			ledger.add( RewardTerm.CURSE_REMOVED,
					config.curseRemovedReward * (prevCursedCarried - cursedCarried), false );
		}
		int overStrong = countEquippedOverStrength();
		if (overStrong > prevEquippedOverStrength){
			ledger.add( RewardTerm.EQUIP_TOO_STRONG,
					config.equipTooStrongPenalty * (overStrong - prevEquippedOverStrength), false );
		}
		int crafted = Statistics.itemsCrafted;
		if (crafted > prevCrafted ){
			ledger.add( RewardTerm.CRAFTED,
					config.craftReward * (crafted - prevCrafted), false );
		}
		if (menuNoop ){
			ledger.add( RewardTerm.MENU_NOOP, config.menuNoopPenalty, false );
		}
		float hunger = hungerLevel( hero );
		if (hunger > prevHunger + 20f && !hero.isStarving()){
			ledger.add( RewardTerm.EATEN_EARLY, config.eatenEarlyPenalty, false );
		}
		if (newlyExplored > 0){
			ledger.add( RewardTerm.EXPLORE, config.exploreReward * newlyExplored, false );
		}
		float spent = engineTurnsSpent( depth );
		lastStepAdvancedClock = depth != prevDepth || spent > 0f;
		if (lastStepAdvancedClock){
			ledger.add( RewardTerm.TURN_COST, -config.turnCost * Math.max( 1f, spent ), false );
		} else {
			ledger.add( RewardTerm.NO_PROGRESS, -config.turnCost * config.freeStepCostFactor, false );
		}
		if (tookAction) ledger.countTurn();
		ledger.add( RewardTerm.STAIR_POTENTIAL,
				config.stairPotentialWeight * stairShaping( hero ), false );
		double reward = ledger.flushTurn();
		snapshot();
		return reward;
	}
	private float stairShaping( Hero hero ){
		if (config.stairPotentialWeight == 0f) return 0f;
		if (Dungeon.depth != prevDepth || Dungeon.branch != prevBranch) return 0f;
		if (hero.pos == prevHeroPos) return 0f;
		return stairPotential.shaping( gamma, prevHeroPos, hero.pos );
	}
	private float engineTurnsSpent( int depthAtStepStart ){
		if (depthAtStepStart != prevDepth) return 0f;
		float spent = Actor.now() - prevEngineTime;
		return spent > 0f ? spent : 0f;
	}
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
				ledger.add( RewardTerm.STALLED, -config.stallPenalty, false );
				break;
			case TURN_LIMIT:
				ledger.add( RewardTerm.TURN_LIMIT, -config.turnLimitPenalty, false );
				break;
			default:
				break;
		}
		if (hero != null && hero.isAlive() && reason == TerminateReason.VICTORY){
			ledger.add( RewardTerm.BOSS_SLAIN, config.depthReward * config.bossSlainFactor, false );
		}
		return ledger.flushTurn();
	}
	public void note( RewardTerm term, double amount ){
		ledger.add( term, amount, false );
	}
	public void noteEvent( RewardTerm term ){
		ledger.noteEvent( term );
	}
	private int prevHeroPos = -1;
	private final StairPotential stairPotential = new StairPotential();
	private final float gamma = 0.99f;
	public float gamma(){
		return gamma;
	}
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
		prevCarried = countCarried();
		prevCursedCarried = countCursedCarried();
		prevEquippedOverStrength = countEquippedOverStrength();
		prevDebuffCount = countDebuffs();
		prevHunger = hungerLevel( hero );
		prevKills = kills();
		prevCrafted = Statistics.itemsCrafted;
		prevHeroPos = hero.pos;
		stairPotential.reset();
		prevSneaks = sneaks();
		prevTalentsSpent = talentsSpent();
		prevEngineTime = Actor.now();
		snapshotValid = true;
	}
	public enum TerminateReason {
		DEATH,
		VICTORY,
		STALLED,
		TURN_LIMIT,
		OTHER
	}
	private float inventoryValue(){
		float value = 0f;
		for (Item item : Dungeon.hero.belongings){
			if (item != null) value += itemValue( config, item );
		}
		return value;
	}
	private static float itemValue( EnvConfig config, Item item ){
		if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag){
			return config.itemValueBag;
		}
		if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem){
			return config.itemValueEquipBase
					+ config.itemValueEquipLevel
							* ((com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem) item).level();
		}
		return config.itemValueOther;
	}
	private int countIdentified(){
		int n = 0;
		for (Item item : Dungeon.hero.belongings){
			if (item != null && item.isIdentified()) n++;
		}
		return n;
	}
	private int countCursedEquipped(){
		Belongings b = Dungeon.hero.belongings;
		int n = 0;
		Item[] worn = { b.weapon(), b.armor(), b.artifact(), b.misc(), b.ring(), b.secondWep() };
		for (Item item : worn){
			if (item != null && item.cursed && item.cursedKnown) n++;
		}
		return n;
	}
	private int countCarried(){
		int n = 0;
		for (Item item : Dungeon.hero.belongings){
			if (item != null) n++;
		}
		return n;
	}
	private int countCursedCarried(){
		int n = 0;
		for (Item item : Dungeon.hero.belongings){
			if (item != null && item.cursed && item.cursedKnown) n++;
		}
		return n;
	}
	private int countEquippedOverStrength(){
		Hero hero = Dungeon.hero;
		if (hero == null) return 0;
		Belongings b = hero.belongings;
		Item[] worn = { b.weapon(), b.armor(), b.artifact(), b.misc(), b.ring() };
		int n = 0;
		for (Item item : worn){
			int required;
			if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor){
				required = ((com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor) item).STRReq();
			} else if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon){
				required = ((com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon) item).STRReq();
			} else {
				continue;
			}
			if (required > hero.STR()) n++;
		}
		return n;
	}
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
	private int kills(){
		return Statistics.enemiesSlain;
	}
	private int sneaks(){
		return Statistics.sneakAttacks;
	}
	private int talentsSpent(){
		Hero hero = Dungeon.hero;
		if (hero == null) return 0;
		int total = 0;
		for (int tier = 1; tier <= hero.talents.size(); tier++){
			total += hero.talentPointsSpent( tier );
		}
		return total;
	}
	private float hungerLevel( Hero hero ){
		Hunger hunger = hero.buff( Hunger.class );
		return hunger == null ? 0f : hunger.hunger();
	}
	public static boolean isCraftable( Item item ){
		return item instanceof Potion
				|| item instanceof Scroll
				|| item instanceof Food;
	}
	public static boolean isUpgrade( Item item ){
		return item instanceof EquipableItem;
	}
}
