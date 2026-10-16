package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.watabou.utils.RandomTrace;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
public class EpisodeDiff {
	private static final int TRACE_STEPS = 320;
	private static final int POLICY_SEED = 4242;
	private static final String[] SEEDS =
			{ "RESETCHECK-A", "RESETCHECK-B", "RESETCHECK-C", "RESETCHECK-D" };
	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-episodediff" ));
		HeadlessServices.disableSaving( true );
		int steps = args.length > 0 ? Integer.parseInt( args[0] ) : TRACE_STEPS;
		boolean includeRoster = args.length < 2 || !args[1].equals( "lean" );
		int differing = 0;
		for (String seed : SEEDS){
			List< String > first = trace( seed, steps, includeRoster );
			for (String other : SEEDS){
				if (!other.equals( seed )) trace( other, steps, includeRoster );
			}
			List< String > second = trace( seed, steps, includeRoster );
			if (report( seed, first, second )) differing++;
		}
		if (differing > 0){
			System.out.println();
			System.out.println( differing + " of " + SEEDS.length + " seeds are not a function of their"
					+ " reset arguments" );
			System.exit( 1 );
		}
		System.out.println();
		System.out.println( "all " + SEEDS.length + " seeds reproduce after running the others in between" );
	}
	private static boolean report( String seed, List< String > first, List< String > second ){
		int n = Math.min( first.size(), second.size() );
		int at = -1;
		for (int i = 0; i < n; i++){
			if (!first.get( i ).equals( second.get( i ))){ at = i; break; }
		}
		if (at < 0){
			System.out.println( seed + ": identical for " + n + " steps"
					+ (first.size() == second.size() ? ""
					: " (lengths " + first.size() + " vs " + second.size() + ")" ) );
			return false;
		}
		System.out.println( seed + ": first divergence at step " + at );
		System.out.println( "  run 1: " + first.get( at ) );
		System.out.println( "  run 2: " + second.get( at ) );
		if (at > 0){
			System.out.println( "  prev : " + first.get( at - 1 ) );
		}
		return true;
	}
	private static List< String > trace( String seed, int steps, boolean includeRoster ){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.WARRIOR );
		RandomTrace.enable();
		RandomTrace.attribute();
		RandomTrace.reset();
		Map< String, Integer > baseline = new HashMap<>( RandomTrace.sites() );
		List< String > rows = new ArrayList<>();
		Map< Integer, String > lastRoster = null;
		Random rng = new Random( POLICY_SEED );
		Action[] all = Action.values();
		for (int i = 0; i < steps && env.running(); i++){
			Action action = env.mode() == EnvMode.WORLD
					? all[ rng.nextInt( all.length ) ] : Action.CANCEL;
			env.step( action, rng.nextInt( 8 ) );
			List< String > drew = new ArrayList<>();
			Map< String, Integer > now = RandomTrace.sites();
			for (Map.Entry< String, Integer > e : now.entrySet()){
				int delta = e.getValue() - baseline.getOrDefault( e.getKey(), 0 );
				if (delta > 0) drew.add( e.getKey() + "=" + delta );
			}
			baseline.clear();
			baseline.putAll( now );
			drew.sort( String::compareTo );
			Map< Integer, String > roster = roster( env.heroPosition() );
			StringBuilder row = new StringBuilder();
			row.append( env.mode() ).append( "/" ).append( env.heroPosition() );
			row.append( " turn=" ).append( Actor.now() );
			row.append( " act=" ).append( Dungeon.hero.curAction == null ? "-" : "Y" );
			row.append( " ready=" ).append( Dungeon.hero.ready );
			row.append( " hp=" ).append( Dungeon.hero.HP );
			row.append( " invis=" ).append( Dungeon.hero.invisible );
			row.append( " base=" ).append( RandomTrace.baseDraws() );
			row.append( " fp=" ).append( RandomTrace.baseFingerprint() );
			row.append( " drew=" ).append( drew.isEmpty() ? "-" : String.join( ",", drew ) );
			if (includeRoster){
				row.append( " moved=" ).append( moved( lastRoster, roster ) );
				row.append( " resched=" ).append( rescheduled( lastRoster, roster ) );
				row.append( " mobs=" ).append( flat( roster ) );
			}
			lastRoster = roster;
			rows.add( row.toString() );
		}
		RandomTrace.attribute( false );
		RandomTrace.disable();
		return rows;
	}
	private static String flat( Map< Integer, String > roster ){
		List< String > entries = new ArrayList<>();
		for (Map.Entry< Integer, String > e : roster.entrySet()){
			entries.add( "#" + e.getKey() + " " + e.getValue() );
		}
		entries.sort( String::compareTo );
		return String.join( " ", entries );
	}
	private static String moved( Map< Integer, String > last, Map< Integer, String > now ){
		if (last == null) return "base";
		List< String > moved = new ArrayList<>();
		for (Map.Entry< Integer, String > e : now.entrySet()){
			String before = last.get( e.getKey() );
			if (before == null) continue;
			String a = before.split( ":" )[0];
			String b = e.getValue().split( ":" )[0];
			if (!a.equals( b )) moved.add( "#" + e.getKey() + " " + a + "->" + b );
		}
		return moved.isEmpty() ? "-" : String.join( " ", moved );
	}
	private static String rescheduled( Map< Integer, String > last, Map< Integer, String > now ){
		if (last == null) return "";
		List< String > out = new ArrayList<>();
		for (Map.Entry< Integer, String > e : now.entrySet()){
			String before = last.get( e.getKey() );
			if (before == null) continue;
			String a = cooldownOf( before );
			String b = cooldownOf( e.getValue() );
			if (!a.equals( b )) out.add( "#" + e.getKey() + " " + a + "->" + b );
		}
		return out.isEmpty() ? "-" : String.join( " ", out );
	}
	private static String cooldownOf( String entry ){
		for (String part : entry.split( ":" )){
			if (part.startsWith( "cd" )) return part;
		}
		return "?";
	}
	private static Map< Integer, String > roster( int heroPos ){
		Map< Integer, String > out = new HashMap<>();
		if (Dungeon.level == null || Dungeon.level.mobs == null) return out;
		for (Mob mob : Dungeon.level.mobs){
			boolean sees = mob.fieldOfView != null && mob.fieldOfView[ heroPos ];
			out.put( mob.id(), mob.pos + ":hp" + mob.HP
					+ ":" + (mob.state == null ? "none" : mob.state.getClass().getSimpleName())
					+ ":cd" + mob.cooldown()
					+ (sees ? ":sees" : "") );
		}
		return out;
	}
}
