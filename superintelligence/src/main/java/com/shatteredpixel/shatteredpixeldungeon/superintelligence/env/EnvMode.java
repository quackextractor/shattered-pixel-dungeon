package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

public enum EnvMode {

	WORLD,

	TARGETING,

	SLOT,

	MENU,

	INVENTORY,

	TALENT;

	public boolean slotIsTarget(){
		return this == TARGETING;
	}
}