package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

public final class PolicyReach {

	private PolicyReach(){}

	public static String check( SPDEnv env, Action action, int secondary ){
		EnvMode mode = env.mode();
		int head = EpisodeCollector.headFor( env );
		if (head == Policy.HEAD_ACTION){
			if (action.kind == Action.Kind.MACRO) return null;
			if (env.actionMask()[ action.index ] <= 0.5f) return action + " is masked in " + mode;
			if (mode == EnvMode.WORLD && action == Action.INTERACT) return interactReach( env, secondary );
			return null;
		}
		float[] mask = head == Policy.HEAD_TARGET ? env.targetMask() : env.slotMask();
		if (!sampleable( mask, secondary )){
			return (head == Policy.HEAD_TARGET ? "target " : "slot ") + secondary + " is masked in " + mode;
		}
		Action emitted = EpisodeCollector.subModeAction( env, secondary );
		if (emitted != action) return "in " + mode + " the collector emits " + emitted + "/" + secondary + ", never " + action;
		return null;
	}

	private static String interactReach( SPDEnv env, int secondary ){
		return secondary == 0 ? null : "INTERACT/" + secondary + " names a direction; the collector always sends 0 and a MOVE picks the cell";
	}

	private static boolean sampleable( float[] mask, int index ){
		boolean any = false;
		for (float m : mask) any |= m > 0.5f;
		return any ? index >= 0 && index < mask.length && mask[ index ] > 0.5f : index == 0;
	}
}
