package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

/**
 * The individual things a run is rewarded and punished for.
 *
 * Each term from docs.md appears here, and each is a separate enum constant rather than a folded
 * constant so that {@link RewardLedger} can report a breakdown. The trainer dashboard needs to
 * show which term produced which points, and a single scalar cannot answer that.
 *
 * Weights live in {@link EnvConfig} and are faded by {@link Curriculum}, which is what implements
 * research.md's Phase 1 / Phase 2 split: dense shaping terms are switched off as the agent
 * becomes competent so it stops hoarding instead of descending.
 */
public enum RewardTerm {

	/** Advanced one floor. The primary goal, and the only term that survives the sparse phase. */
	DEPTH_ADVANCE,

	/** Cleared a boss floor. */
	BOSS_SLAIN,

	/** Reached and cleared the final floor. */
	VICTORY,

	/** Revealed a never-before-seen tile. */
	EXPLORE,

	/** Gained maximum HP through a potion, scroll or level up. */
	MAX_HP_GAIN,

	/** Gained gold, weighted by the value of what was picked up. */
	GOLD_GAIN,

	/** Picked up an item, weighted by tier and by whether it expands inventory. */
	ITEM_PICKUP,

	/**
	 * Put an item back on the floor, or consumed one. The negative of what picking it up paid.
	 *
	 * <p>A separate term rather than a silent no-op, because the pickup term used to be a count
	 * compared one way: gaining an item paid and losing one was free, so {@code INTERACT} then
	 * {@code DROP} then {@code INTERACT} again raised the score forever without the world changing.
	 * Charging the loss is the fix; naming it separately is what makes the loop visible in the
	 * per-term report instead of showing up only as a total that does not add up to its parts.
	 */
	ITEM_DROPPED,

	/** Identified a potion or scroll, either directly or by using it. */
	IDENTIFIED,

	/** Cured a negative status effect. */
	CURED_DEBUFF,

	/** Gained a level. */
	LEVEL_UP,

	/** Gained strength. */
	STRENGTH_GAIN,

	/** Removed a curse from something equipped and still held. */
	CURSE_REMOVED,

	/** Crafted at the alchemy pot. */
	CRAFTED,

	/** Crafted a meat pie specifically, which docs.md singles out. */
	CRAFTED_MEAT_PIE,

	/** Killed a hostile mob. */
	KILL,

	/** Lost health. */
	DAMAGE_TAKEN,

	/** Lost gold. Punished to stop shop loops. */
	GOLD_LOST,

	/** Ate food while not yet starving. Punished per docs.md. */
	EATEN_EARLY,

	/** Equipped something cursed. Punished per docs.md. */
	CURSE_EQUIPPED,

	/** Tried to equip something above the hero's strength. Punished per docs.md. */
	EQUIP_TOO_STRONG,

	/** A turn elapsed. Constant small penalty for efficiency. */
	TURN_COST,

	/** Died. */
	DEATH,

	/** Ran out of turns on a floor, or otherwise failed to progress. */
	STALLED,

	/** Reached the turn cap without dying, which is not a death but is not progress either. */
	TURN_LIMIT,

	/** The agent chose an action the environment refused, eg. an unusable item. */
	INVALID_ACTION,

	/** Opened and immediately closed a dialog with no net effect. Guards shop loops. */
	MENU_NOOP;

	private static final RewardTerm[] VALUES = values();

	public static RewardTerm at( int i ){
		return VALUES[ i ];
	}

	public static int count(){
		return VALUES.length;
	}

	/** True for terms the curriculum fades out once the agent is competent. */
	public boolean isShaping(){
		switch (this) {
			case EXPLORE:
			case MAX_HP_GAIN:
			case GOLD_GAIN:
			case ITEM_PICKUP:
			case ITEM_DROPPED:
			case IDENTIFIED:
			case CURED_DEBUFF:
			case LEVEL_UP:
			case STRENGTH_GAIN:
			case CURSE_REMOVED:
			case CRAFTED:
			case CRAFTED_MEAT_PIE:
			case KILL:
				return true;
			default:
				return false;
		}
	}
}