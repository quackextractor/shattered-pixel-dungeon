package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TeacherCheck {

	private static final int CHECKS = 2;

	private static final String[] SEEDS = { "TEACHER-A", "TEACHER-B", "TEACHER-C", "TEACHER-D", "TEACHER-E", "TEACHER-F" };

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "teachercheck" ));
		HeadlessServices.disableSaving( true );

		checkTheTeacherDescends();
		checkAnUpdateMovesThePolicyTowardTheTeacher();

		if (failures.isEmpty()){
			System.out.println( "[OK]     teacher: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  teacher: " + failures.size() + " of " + CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkTheTeacherDescends(){
		int floors = 0;
		StringBuilder detail = new StringBuilder();
		for (String seed : SEEDS){
			SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
			env.reset( seed, HeroClass.WARRIOR );
			DescentBot bot = new DescentBot();
			int[] secondary = new int[ 1 ];
			int steps = 0;
			while (env.running() && steps < 6000){
				env.step( bot.act( env, secondary ), secondary[ 0 ] );
				steps++;
			}
			floors += env.deepestDepth() - 1;
			detail.append( ' ' ).append( seed ).append( "=d" ).append( env.deepestDepth() ).append( '/' )
					.append( env.running() ? "steps" : env.endReason().name() );
		}
		if (floors < SEEDS.length){
			fail( "DescentBot descended " + floors + " floors over " + SEEDS.length + " seeds:" + detail
					+ ". A teacher that does not leave floor 1 teaches nothing about descending." );
			return;
		}
		System.out.println( "  DescentBot descends " + floors + " floors over " + SEEDS.length + " seeds:" + detail );
	}

	private static void checkAnUpdateMovesThePolicyTowardTheTeacher(){
		float[] taught = teacherProbabilityAcrossAnUpdate( 1f );
		float[] untaught = teacherProbabilityAcrossAnUpdate( 0f );
		float gain = taught[ 1 ] - taught[ 0 ];
		float drift = untaught[ 1 ] - untaught[ 0 ];
		if (!(gain > 0.01f) || !(gain > 5f * Math.abs( drift ))){
			fail( "an update at zero advantage moved p(teacher) " + taught[ 0 ] + " -> " + taught[ 1 ]
					+ " with rl.teacher_weight 1 and " + untaught[ 0 ] + " -> " + untaught[ 1 ] + " with 0;"
					+ " the imitation term is not reaching the action head" );
			return;
		}
		System.out.println( "  one update moves p(teacher) " + taught[ 0 ] + " -> " + taught[ 1 ]
				+ " with weight 1, " + untaught[ 0 ] + " -> " + untaught[ 1 ] + " with 0" );
	}

	private static float[] teacherProbabilityAcrossAnUpdate( float weight ){
		EnvConfig config = new EnvConfig();
		PPO ppo = new PPO( config, new Random( 41L ));
		ppo.teacherWeight = weight;
		ppo.entropyCoeff = 0f;
		ppo.epochs = 1;
		ppo.normaliseValues = false;
		int teacher = Action.TRAVEL_EXPLORE.index;
		Random rng = new Random( 42L );
		float[] grid = new float[ ppo.network.gridLength() ];
		for (int i = 0; i < grid.length; i++) grid[ i ] = rng.nextInt( 5 ) == 0 ? 1f : 0f;
		Transition sample = Transition.take( grid.length, config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT,
				HeroEncoder.FEATURES, Action.size(), config.maxSlots, ActionMapper.TARGET_COUNT, ppo.network.stateSize() );
		sample.packGrid( grid );
		java.util.Arrays.fill( sample.actionMask, 1f );
		ppo.network.resetState();
		ppo.network.saveState( sample.recurrentState );
		float before = probability( ppo, grid, sample, teacher );
		List< Transition > batch = new ArrayList<>();
		for (int i = 0; i < 8; i++){
			Transition t = i == 0 ? sample : copy( sample, config, ppo );
			t.liveHead = Policy.HEAD_ACTION;
			t.actionIndex = Action.WAIT.index;
			t.oldLogProbability = (float) Math.log( 1.0 / Action.size() );
			t.teacherIndex = teacher;
			t.episodeId = i;
			t.stepIndex = 0;
			batch.add( t );
		}
		ppo.addAll( batch );
		ppo.update();
		return new float[]{ before, probability( ppo, grid, sample, teacher ) };
	}

	private static Transition copy( Transition from, EnvConfig config, PPO ppo ){
		Transition t = Transition.take( from.gridCells, from.inventory.length, from.hero.length, from.actionMask.length,
				from.slotMask.length, from.targetMask.length, ppo.network.stateSize() );
		System.arraycopy( from.grid, 0, t.grid, 0, from.grid.length );
		System.arraycopy( from.actionMask, 0, t.actionMask, 0, from.actionMask.length );
		System.arraycopy( from.recurrentState, 0, t.recurrentState, 0, from.recurrentState.length );
		return t;
	}

	private static float probability( PPO ppo, float[] grid, Transition t, int index ){
		ppo.network.resetState();
		ppo.network.forward( grid, t.inventory, t.hero );
		float[] p = new float[ Action.size() ];
		Policy.probabilities( ppo.network.actionLogits(), t.actionMask, p );
		return p[ index ];
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
