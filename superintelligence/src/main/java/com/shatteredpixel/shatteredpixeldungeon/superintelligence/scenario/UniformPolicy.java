package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

import java.util.Random;

final class UniformPolicy {

	private final Random random;

	UniformPolicy( long seed ){
		random = new Random( seed );
	}

	Action choose( SPDEnv env, int[] secondary ){
		int head = EpisodeCollector.headFor( env );
		if (head != Policy.HEAD_ACTION){
			secondary[ 0 ] = pick( head == Policy.HEAD_TARGET ? env.targetMask() : env.slotMask() );
			return EpisodeCollector.subModeAction( env, secondary[ 0 ] );
		}
		float[] mask = env.actionMask().clone();
		for (Action action : Action.values()) if (action.kind == Action.Kind.MACRO) mask[ action.index ] = 0f;
		Action action = Action.fromIndex( pick( mask ));
		secondary[ 0 ] = action == Action.INTERACT && env.mode() == EnvMode.WORLD ? pick( env.targetMask() ) : 0;
		return action;
	}

	private int pick( float[] mask ){
		int offered = 0;
		for (float m : mask) if (m > 0.5f) offered++;
		if (offered == 0) return 0;
		int chosen = random.nextInt( offered );
		for (int i = 0; i < mask.length; i++) if (mask[ i ] > 0.5f && chosen-- == 0) return i;
		return 0;
	}
}
