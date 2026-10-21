package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class GoExplore {

	private static final int EXPLORE_STEPS = 60;
	private static final float RANDOM_ACTION = 0.25f;

	private static final class Cell {
		final int[] actions;
		final int[] slots;
		final int depth;
		int visits;

		Cell( int[] actions, int[] slots, int depth ){
			this.actions = actions;
			this.slots = slots;
			this.depth = depth;
		}
	}

	private GoExplore(){}

	public static void main( String[] args ){
		if (args.length < 3){
			System.err.println( "[ERROR] usage: goexplore <out-dir> <iterations> <seed> [seed...]" );
			System.exit( 1 );
			return;
		}
		HeadlessServices.install( Scratch.dir( "goexplore" ));
		HeadlessServices.disableSaving( true );
		File out = com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions.resolve( new File( args[ 0 ] ));
		if (!out.isDirectory() && !out.mkdirs()){
			System.err.println( "[ERROR] cannot create " + out );
			System.exit( 1 );
			return;
		}
		int iterations = Integer.parseInt( args[ 1 ] );
		for (int i = 2; i < args.length; i++){
			Cell best = explore( args[ i ], iterations );
			File file = new File( out, args[ i ] + "-goexplore.replay" );
			try {
				ReplayIO.write( record( args[ i ], best ), file );
			} catch (IOException e){
				System.err.println( "[ERROR] cannot write " + file + " (" + e.getMessage() + ")" );
				System.exit( 1 );
			}
			System.out.println( "[OK]   " + args[ i ] + ": depth " + best.depth + " in " + best.actions.length + " steps -> " + file );
		}
	}

	static Cell explore( String seed, int iterations ){
		Map< Long, Cell > archive = new HashMap<>();
		Random rng = new Random( seed.hashCode() );
		archive.put( -1L, new Cell( new int[ 0 ], new int[ 0 ], 1 ));
		Cell best = archive.get( -1L );
		for (int it = 0; it < iterations; it++){
			Cell from = choose( archive, rng );
			from.visits++;
			SPDEnv env = restore( seed, from );
			if (!env.running()) continue;
			List< Integer > actions = new ArrayList<>();
			List< Integer > slots = new ArrayList<>();
			for (int a : from.actions) actions.add( a );
			for (int s : from.slots) slots.add( s );
			DescentBot bot = new DescentBot();
			int[] secondary = new int[ 1 ];
			for (int s = 0; s < EXPLORE_STEPS && env.running(); s++){
				Action action = bot.act( env, secondary );
				if (rng.nextFloat() < RANDOM_ACTION) action = randomLegal( env, rng, action );
				actions.add( action.index );
				slots.add( secondary[ 0 ] );
				env.step( action, secondary[ 0 ] );
				if (!env.running() || Dungeon.hero == null) break;
				long key = ((long) Dungeon.depth << 32) | (Dungeon.hero.pos / 3L);
				Cell seen = archive.get( key );
				if (seen == null || seen.actions.length > actions.size()){
					Cell cell = new Cell( toArray( actions ), toArray( slots ), Dungeon.depth );
					if (seen != null) cell.visits = seen.visits;
					archive.put( key, cell );
					if (cell.depth > best.depth || (cell.depth == best.depth && cell.depth > 1 && cell.actions.length < best.actions.length)) best = cell;
				}
			}
		}
		return best;
	}

	private static Cell choose( Map< Long, Cell > archive, Random rng ){
		double total = 0;
		for (Cell c : archive.values()) total += weight( c );
		double pick = rng.nextDouble() * total;
		for (Cell c : archive.values()){
			pick -= weight( c );
			if (pick <= 0) return c;
		}
		return archive.values().iterator().next();
	}

	private static double weight( Cell c ){
		return c.depth * c.depth / Math.sqrt( c.visits + 1.0 );
	}

	private static SPDEnv restore( String seed, Cell cell ){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );
		for (int i = 0; i < cell.actions.length && env.running(); i++){
			env.step( Action.fromIndex( cell.actions[ i ] ), cell.slots[ i ] );
		}
		return env;
	}

	private static com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay record( String seed, Cell cell ){
		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );
		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HeroClass.WARRIOR.name(), 0, config.turnLimitPerFloor, config );
		for (int i = 0; i < cell.actions.length && env.running(); i++){
			Action action = Action.fromIndex( cell.actions[ i ] );
			recorder.record( action, cell.slots[ i ], env.mode() );
			float reward = (float) env.step( action, cell.slots[ i ] );
			recorder.afterStep( env.heroPosition(), reward, env.lastStepActed() ? 1 : 0 );
		}
		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.running() ? "DEMONSTRATION" : env.endReason().name() );
		return recorder.replay();
	}

	private static Action randomLegal( SPDEnv env, Random rng, Action fallback ){
		float[] mask = env.actionMask();
		List< Action > legal = new ArrayList<>();
		for (Action a : Action.values()){
			if (a.index < mask.length && mask[ a.index ] > 0.5f && a.directional()) legal.add( a );
		}
		return legal.isEmpty() ? fallback : legal.get( rng.nextInt( legal.size() ));
	}

	private static int[] toArray( List< Integer > list ){
		int[] out = new int[ list.size() ];
		for (int i = 0; i < out.length; i++) out[ i ] = list.get( i );
		return out;
	}
}
