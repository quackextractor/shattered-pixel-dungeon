package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;


public enum RewardTerm {


	DEPTH_ADVANCE,


	BOSS_SLAIN,


	VICTORY,


	EXPLORE,


	STAIR_POTENTIAL,


	MAX_HP_GAIN,


	GOLD_GAIN,


	ITEM_PICKUP,


	ITEM_DROPPED,


	IDENTIFIED,


	CURED_DEBUFF,


	LEVEL_UP,


	TALENT_UPGRADE,


	STRENGTH_GAIN,


	CURSE_REMOVED,


	CRAFTED,


	CRAFTED_MEAT_PIE,


	KILL,


	SNEAK_ATTACK,


	NO_PROGRESS,


	DAMAGE_TAKEN,


	GOLD_LOST,


	EATEN_EARLY,


	CURSE_EQUIPPED,


	EQUIP_TOO_STRONG,


	TURN_COST,


	DEATH,


	STALLED,


	TURN_LIMIT,


	INVALID_ACTION,


	MENU_NOOP;

	private static final RewardTerm[] VALUES = values();

	public static RewardTerm at( int i ){
		return VALUES[ i ];
	}

	public static int count(){
		return VALUES.length;
	}


	public boolean isShaping(){
		switch (this) {
			case STAIR_POTENTIAL:

		case EXPLORE:
			case MAX_HP_GAIN:
			case GOLD_GAIN:
			case ITEM_PICKUP:
			case ITEM_DROPPED:
			case IDENTIFIED:
			case CURED_DEBUFF:
			case LEVEL_UP:
			case TALENT_UPGRADE:
			case STRENGTH_GAIN:
			case CURSE_REMOVED:
			case CRAFTED:
			case CRAFTED_MEAT_PIE:
			case KILL:
			case SNEAK_ATTACK:
				return true;
			default:
				return false;
		}
	}
}
