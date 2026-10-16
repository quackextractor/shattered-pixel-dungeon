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

		if (episodes < 0){
			int bad = probeGuards( 6 );
			System.exit( bad == 0 ? 0 : 1 );
		}

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
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode before = env.mode();
				env.step( a, pickSlot( env, rng ));
				used.merge( a.name(), 1, Integer::sum );
				String key = a.name() + " from " + before.name();
				if (!env.lastStepAdvancedClock()) freeByAction.merge( key, 1, Integer::sum );
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
		System.out.println( "steps that advanced no engine time, by action and the mode it was issued from:" );
		for (Map.Entry< String, Integer > e : freeByAction.entrySet()){
			String action = e.getKey().split( " from " )[ 0 ];
			System.out.println( String.format( "  %-14s %5d free", e.getKey(), e.getValue() ));
			if (!action.equals( "USE" )) continue;
			System.out.println( String.format( "                 USE total %5d used", used.getOrDefault( action, 0 ) ));
		}
	}

	/**
 * Forces each guard in turn by moving its threshold, and reports which one actually answered.
 *
 * <p>The point is reachability. Three of the four assignments never run under the default thresholds -
 * a uniform policy ends every episode on {@code FREE_STEP} and the other three sit at zero - so a
 * mislabelled constant on any of them would compile, pass every gate and be wrong. Each row here drives
 * the thresholds until that guard is the only one left that can fire.
 */
private static int probeGuards( int episodes ){
		String[] names = { "defaults", "position", "freeStep", "actorStep" };
		StallCause[] expected = { StallCause.FREE_STEP, StallCause.POSITION,
				StallCause.ACTOR_STEP_LIMIT, StallCause.PIPELINE };
		int[][] settings = {
				{ -1, -1, -1 },
				{ 1, 1_000_000, -1 },
				{ -1, -1, 0 },
				{ -1, -1, 1 }
		};

		int failures = 0;
		System.out.println();
		System.out.println( "guard reachability, forcing one threshold at a time:" );
		System.out.println( String.format( "  %-11s %-16s %-16s %-16s  %s",
				"config", "stallLimit", "freeStepBudget", "actorStepLimit", "cause" ));
		for (int row = 0; row < settings.length; row++){
			EnvConfig c = new EnvConfig();
			if (settings[ row ][ 0 ] > 0 || settings[ row ][ 0 ] == 1) c.stallLimit = settings[ row ][ 0 ];
			if (settings[ row ][ 1 ] == 1_000_000 ) c.freeStepBudget = settings[ row ][ 1 ];
			if (settings[ row ][ 2 ] >= 0 ) c.actorStepLimit = settings[ row ][ 2 ];

			Map< StallCause, Integer > seen = new EnumMap<>( StallCause.class );
			for (int i = 0; i < episodes; i++){
				SPDEnv env = new SPDEnv( c, HeadlessGame.install() );
				env.reset( String.format( "GUARDPROBE-%04d", i ), HeroClass.WARRIOR );
				Random rng = new Random( 0x6A4D + i );
				int n = 0;
				while (env.running() && n < 4000){
					env.step( pickAction( env, rng ), pickSlot( env, rng ));
					n++;
				}
				seen.merge( env.stallCause(), 1, Integer::sum );
			}

			StallCause dominant = StallCause.NONE;
			int best = 0;
			for (Map.Entry< StallCause, Integer > e : seen.entrySet()){
				if (e.getValue() > best){ best = e.getValue(); dominant = e.getKey(); }
			}

			boolean ok = row == 0 ? seen.getOrDefault( StallCause.FREE_STEP, 0 ) > 0
					: dominant == expected[ row ];

			System.out.println( String.format( "  %-11s %-16d %-16d %-16d  %s (%d/%d)  %s %s",
					names[ row ], c.stallLimit, c.freeStepBudget, c.actorStepLimit,
					dominant.name(), best, episodes,
					row == 0 ? "expects FREE_STEP reachable" : "expected " + expected[ row ].name(),
					ok ? "[OK]" : "[ERROR]" ));
			if (!ok) failures++;
		}

		System.out.println();
		if (failures == 0){
			System.out.println( "  all four guards reachable, and each reports its own name." );
		} else {
			System.out.println( "  " + failures + " row(s) named the wrong guard, or none at all." );
			System.out.println( "  A mislabelled StallCause constant compiles and passes every other gate," );
			System.out.println( "  because under the default thresholds only FREE_STEP is ever reached." );
		}
		return failures;
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
