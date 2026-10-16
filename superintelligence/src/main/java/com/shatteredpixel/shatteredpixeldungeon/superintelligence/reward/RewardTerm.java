package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

public enum RewardTerm {

	DEPTH_ADVANCE( Type.OBJECTIVE ),


	BOSS_SLAIN( Type.OBJECTIVE ),


	VICTORY( Type.OBJECTIVE ),


	EXPLORE( Type.EXPLORATION ),


	STAIR_POTENTIAL( Type.EXPLORATION ),


	MAX_HP_GAIN( Type.PROGRESSION ),


	GOLD_GAIN( Type.ITEMS ),


	ITEM_PICKUP( Type.ITEMS ),


	ITEM_DROPPED( Type.ITEMS ),


	IDENTIFIED( Type.ITEMS ),


	CURED_DEBUFF( Type.SURVIVAL ),


	LEVEL_UP( Type.PROGRESSION ),


	TALENT_UPGRADE( Type.PROGRESSION ),


	STRENGTH_GAIN( Type.PROGRESSION ),


	CURSE_REMOVED( Type.ITEMS ),


	CRAFTED( Type.SURVIVAL ),


	CRAFTED_MEAT_PIE( Type.SURVIVAL ),


	KILL( Type.COMBAT ),


	SNEAK_ATTACK( Type.COMBAT ),


	NO_PROGRESS( Type.COSTS ),


	DAMAGE_TAKEN( Type.COMBAT ),


	GOLD_LOST( Type.ITEMS ),


	EATEN_EARLY( Type.SURVIVAL ),


	CURSE_EQUIPPED( Type.ITEMS ),


	EQUIP_TOO_STRONG( Type.ITEMS ),


	TURN_COST( Type.COSTS ),


	DEATH( Type.TERMINAL ),


	STALLED( Type.TERMINAL ),


	TURN_LIMIT( Type.TERMINAL ),


	INVALID_ACTION( Type.COSTS ),


	MENU_NOOP( Type.COSTS );

	private final Type type;

	RewardTerm( Type type ){
		this.type = type;
	}

	public Type type(){
		return type;
	}

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


	public enum Type {
		OBJECTIVE,
		EXPLORATION,
		COMBAT,
		PROGRESSION,
		ITEMS,
		SURVIVAL,
		COSTS,
		TERMINAL
	}
}
