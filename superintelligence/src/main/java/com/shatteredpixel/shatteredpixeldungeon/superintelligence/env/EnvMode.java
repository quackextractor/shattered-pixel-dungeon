package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

public enum EnvMode {

	WORLD,

	TARGETING,

	SLOT,

	MENU,

	INVENTORY,

	TALENT,

	ALCHEMY;

	public boolean slotIsTarget(){
		return this == TARGETING;
	}
}