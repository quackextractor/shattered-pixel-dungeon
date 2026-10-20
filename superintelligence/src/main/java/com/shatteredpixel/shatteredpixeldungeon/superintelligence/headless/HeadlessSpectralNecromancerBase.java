package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.shatteredpixel.shatteredpixeldungeon.sprites.SpectralNecromancerSprite;

public abstract class HeadlessSpectralNecromancerBase extends SpectralNecromancerSprite {

	@Override
	public void finishSummoning(){
		idle();
	}
}
