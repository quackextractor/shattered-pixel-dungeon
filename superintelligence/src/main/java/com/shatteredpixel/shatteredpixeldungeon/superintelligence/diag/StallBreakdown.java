package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Runs episodes under a uniform policy and names which of the four stall guards ended each one.
 *
 * <p><pre>gradle :superintelligence:stallbreakdown --args="40"</pre>
 *
 * <p>The question. A run reports {@code endSTALLED} as one column, but four guards raise that same
 * reason, and they are not interchangeable - one is the engine refusing to yield, one is a pipeline that
 * could not settle the hero, one is the hero standing still, and one is the agent spending actions that
 * cost nothing. Three repeats of one training config put 98-99% of all episodes at STALLED
 * ({@code issues.md} 27), which makes the guard that fires the difference between a tuning problem and
 * a harness bug.
 *
 * <p>Not a gate. It runs whole episodes and reports a distribution; what counts as too many of which
 * guard is a judgement about the reward function rather than a fact about the code. It also uses a fixed
 * seed per episode, so the distribution is reproducible but is one policy, not the policy a run's
 * checkpoint holds.
 */
public class StallBreakdown {

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-stallbreakdown" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		int episodes = args.length > 0 ? Integer.parseInt( args[ 0 ] ) : 40;

		System.out.println( "guards, at the default thresholds:" );
		System.out.println( "  stallLimit " + config.stallLimit + "   freeStepBudget "
				+ config.freeStepBudget + "   actorStepLimit " + config.actorStepLimit
				+ "   turnLimitPerFloor " + config.turnLimitPerFloor );
		System.out.println();

		Map< StallCause, Integer > causes = new EnumMap<>( StallCause.class );
		Map< String, Integer > endings = new LinkedHashMap<>();
		Map< String, Integer > freeByAction = new LinkedHashMap<>();
		Map< String, Integer > used = new LinkedHashMap<>();
		int turnsTotal = 0;
		int deepest = 0;
		int stalls = 0;

		for (int i = 0; i < episodes; i++){
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			env.reset( String.format( "STALLPROBE-%04d", i ), HeroClass.WARRIOR );

			Random rng = new Random( 0x51A11L + i );
			int n = 0;
			while (env.running() && n < config.turnLimitTotal){
				Action a = pickAction( env, rng );
				env.step( a, pickSlot( env, rng ));
				used.merge( a.name(), 1, Integer::sum );
				if (!env.lastStepAdvancedClock()) freeByAction.merge( a.name(), 1, Integer::sum );
				n++;
			}

			causes.merge( env.stallCause(), 1, Integer::sum );
			endings.merge( env.endReason().name(), 1, Integer::sum );
			turnsTotal += env.turnsTotal();
			deepest = Math.max( deepest, env.deepestDepth() );
			if (env.endReason() == RewardModel.TerminateReason.STALLED) stalls++;
		}

		System.out.println( episodes + " episodes, uniform policy, fixed seed per episode" );
		System.out.println( "  meanTurns " + (turnsTotal / (double) episodes)
				+ "   deepestDepth " + deepest + "   STALLED " + stalls );
		System.out.println();

		System.out.println( "which guard ended them:" );
		for (StallCause cause : StallCause.values()){
			int n = causes.getOrDefault( cause, 0 );
			if (n == 0 && cause == StallCause.NONE) continue;
			System.out.println( String.format( "  %-18s %4d  %5.1f%%", cause.name(), n,
					100.0 * n / episodes ));
		}
		System.out.println();
		System.out.println( "endings, for the same runs:" );
		for (Map.Entry< String, Integer > e : endings.entrySet()){
			System.out.println( String.format( "  %-18s %4d", e.getKey(), e.getValue() ));
		}
		System.out.println();
		System.out.println( "steps that advanced no engine time, by action (of all steps taken):" );
		for (Map.Entry< String, Integer > e : used.entrySet()){
			int free = freeByAction.getOrDefault( e.getKey(), 0 );
			if (free == 0) continue;
			System.out.println( String.format( "  %-14s %5d free / %5d used  = %4.1f%%", e.getKey(),
					free, e.getValue(), 100.0 * free / e.getValue() ));
		}
	}

	private static Action pickAction( SPDEnv env, Random rng ){
		float[] mask = env.actionMask();
		int n = 0;
		for (float v : mask) if (v > 0.5f) n++;
		if (n == 0) return Action.CANCEL;
		int pick = rng.nextInt( n );
		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] <= 0.5f) continue;
			if (pick-- == 0) return Action.values()[ i ];
		}
		return Action.CANCEL;
	}

	private static int pickSlot( SPDEnv env, Random rng ){
		float[] mask = env.slotMask();
		if (mask == null) return 0;
		int n = 0;
		for (float v : mask) if (v > 0.5f) n++;
		if (n == 0) return 0;
		int pick = rng.nextInt( n );
		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] <= 0.5f) continue;
			if (pick-- == 0) return i;
		}
		return 0;
	}
}
