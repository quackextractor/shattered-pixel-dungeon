/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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

/**
 * Runs a seed, runs other seeds, then runs it again, and reports the first step where the two runs of
 * the same seed stopped agreeing - with enough of the world attached to say <em>why</em>.
 *
 * <p>{@code resetcheck} reports that two runs of a seed differ and where. It does not say what differed,
 * and that is the whole question. A failing isolation gate with no mechanism behind it is a rumour.
 *
 * <p>Each step's row carries, on both sides:
 * <ul>
 * <li>the hero's own state - mode, position, turn, pending action, readiness, invisibility</li>
 * <li>the gameplay randomness consumed so far - base draws and an order-sensitive fingerprint</li>
 * <li>the call sites that drew during that step, so "the streams diverged" and "the world diverged" are
 * distinguishable from "the streams agreed and the world still differed"</li>
 * <li>the full mob roster - every mob's position, health and AI state - so a difference in which mobs
 * are awake or where they stand is visible rather than inferred</li>
 * </ul>
 *
 * <p>The three-way split matters. Randomness first points at a generator or seeding problem. World
 * first on identical randomness points somewhere else entirely - state that outlives {@code reset()}.
 * That is the case this was written for: the first divergence found here was identical RNG and identical
 * hero state with a mob that could see the hero in one run and not the other.
 *
 * <p>The policy is uniformly random, as in {@code resetcheck}, because a scripted policy never reaches
 * half the engine and would not trip the failure it exists to explain.
 */
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

	// --------------------------------------------------------------------------- reporting

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

	// --------------------------------------------------------------------------- tracing

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

	/**
	 * Every mob, sorted, as {@code #id Name@cell:hp:state[:sees]}.
	 *
	 * <p>Sorted because a list order is not meaningful and an unsorted roster would report a difference
	 * whenever two runs happened to spawn the same mobs in a different order.
	 *
	 * <p>The actor id is in there on purpose. {@code Actor.nextID} is a static counter that
	 * {@code reset()} does not clear, so the same mob has a different id in a second episode in the same
	 * process. Anything that breaks a tie by id - and the scheduler does, when two actors come due on the
	 * same turn - then resolves differently for reasons that have nothing to do with the seed. A roster
	 * without ids cannot tell that apart from a genuine behavioural difference.
	 *
	 * <p>{@code fieldOfView} is allocated lazily, so a mob that has never been seen is marked
	 * distinctly rather than being treated as one that cannot see; the two are opposite facts and
	 * conflating them would hide the very thing being looked for.
	 */
	private static String flat( Map< Integer, String > roster ){
		List< String > entries = new ArrayList<>();
		for (Map.Entry< Integer, String > e : roster.entrySet()){
			entries.add( "#" + e.getKey() + " " + e.getValue() );
		}
		entries.sort( String::compareTo );
		return String.join( " ", entries );
	}

	/** Which mobs stood somewhere else than they did last step, as {@code #id from->to}. */
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

	/** Which mobs were scheduled differently from last step, as {@code #id cdA->cdB}. */
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
					//cooldown is the mob's scheduled turn minus now, and it is the column that matters
					//most. A mob that wanders toward a queued target steps one cell without drawing, so a
					//divergence in who is due shows up as a moved mob and nothing else - and only at the step
					//where the difference happens to be paid out. Comparing cooldowns finds it at the step
					//where it was created instead.
					+ ":cd" + mob.cooldown()
					+ (sees ? ":sees" : "") );
		}
		return out;
	}
}