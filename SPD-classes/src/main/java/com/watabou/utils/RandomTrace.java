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

package com.watabou.utils;

/**
 * Counts and fingerprints every draw made through {@link Random}.
 *
 * <p>Disabled by default, and the check is a single static boolean per draw, so the cost when nobody is
 * measuring is one predictable branch. Enable it only from a gate or a diagnostic.
 *
 * <p>Only {@code Random} is instrumented. Presentation randomness lives on {@link PRandom}, which has
 * its own stream, so what this counts is exactly the draws the simulation is entitled to: every other
 * number on screen is cosmetic and must not perturb this one.
 *
 * <p>Used for two things. {@code observecheck} asserts that <em>reading</em> the world draws nothing -
 * the invariant whose one violation, {@code HeroEncoder} calling {@code drRoll()}, silently offset the
 * RNG stream from the first floor of every run. {@code rngtrace} compares the per-step count and
 * fingerprint between a headless run and the rendered game.
 */
public class RandomTrace {

	private static boolean enabled = false;

	/** Draws made, whichever generator answered. */
	private static long draws = 0;

	/** Draws made with {@code useGeneratorStack == false}, i.e. against the base generator. */
	private static long baseDraws = 0;

	/**
	 * Order-sensitive fold of every value drawn.
	 *
	 * <p>A count alone cannot distinguish "drew the same numbers in a different order" from "drew the
	 * same numbers in the same order", and the first is a divergence too.
	 */
	private static long fingerprint = 0;

	/**
	 * The same fold, over base-generator draws only.
	 *
	 * <p>This is the one that answers "do these two paths simulate the same randomness". A pushed
	 * generator is an independent stream - {@code Dungeon.seedForDepth} derives a per-depth seed by
	 * skipping ahead on one - so drawing on it cannot change any outcome. Folding those draws into the
	 * fingerprint reported differences that did not exist: the rendered viewer calls
	 * {@code seedForDepth} once more than the headless path does, on a stream that is discarded either
	 * way, and that was enough to make a byte-identical simulation look divergent.
	 */
	private static long baseFingerprint = 0;

	/**
	 * When set, every draw is attributed to the call site that made it and tallied per site.
	 *
	 * <p>Off by default, and materially more expensive than counting: it walks the stack on every draw,
	 * so it is a debugging mode to be switched on around a specific window and then switched off. That
	 * window is usually the interesting one - level generation through to the first simulated step -
	 * which is a few thousand draws rather than a whole run.
	 *
	 * <p>The purpose is to find <em>which code</em> consumes randomness, not how much. A count says a
	 * stream is offset; a per-site tally says by how much and where, and the remedy is always to fix the
	 * site rather than to compensate for it downstream - adjusting an index to hide an offset just moves
	 * the divergence somewhere less visible.
	 */
	private static boolean attributing = false;

	/** Draw site to draw count, in first-seen order. */
	private static final java.util.LinkedHashMap< String, Integer > bySite =
			new java.util.LinkedHashMap<>();

	/**
	 * Every attributed draw site, in the order the draws were made.
	 *
	 * <p>{@link #sites()} answers "how much did this code draw", which is what a per-run tally can
	 * say. It cannot answer "where did two runs of one seed stop agreeing", because a tally has no
	 * order: two runs whose streams part at the first draw of floor 2 still report identical totals for
	 * every site afterwards. This log is the ordered form, and its first differing entry names the draw
	 * the two runs parted at.
	 */
	private static final java.util.ArrayList< String > siteLog = new java.util.ArrayList<>();

	/**
	 * Every generator stack event, in order, carrying the base stream's position at that moment.
	 *
	 * <p>A pushed generator is an independent stream, so the only thing that can reach a floor's layout
	 * from outside it is the state the base generator was left in before the push. {@link #baseDraws()}
	 * answers that for the whole run and nothing for a moment inside it; this answers it per push.
	 */
	private static final java.util.ArrayList< String > generatorLog = new java.util.ArrayList<>();

	private RandomTrace() {}

	public static void enable(){
		enabled = true;
	}

	public static void disable(){
		enabled = false;
	}

	/** True while counting. */
	public static boolean enabled(){
		return enabled;
	}

	/** Zeroes the counters. Does not disable. */
	public static void reset(){
		draws = 0;
		baseDraws = 0;
		fingerprint = 0;
		baseFingerprint = 0;
		siteLog.clear();
		generatorLog.clear();
	}

	/**
	 * Records one draw.
	 *
	 * @param bits the raw bits of the value drawn
	 * @param base  true when it came from the base generator rather than the pushed stack
	 */
	public static void record( long bits, boolean base ){
		if (!enabled) return;
		draws++;
		if (base) baseDraws++;
		fingerprint ^= bits;
		fingerprint *= 1099511628211L;
		if (base){
			baseFingerprint ^= bits;
			baseFingerprint *= 1099511628211L;
		}
		if (attributing) attributeDraw();
	}

	/**
	 * Starts attributing draws to call sites, clearing any previous tally.
	 *
	 * <p>Walks the stack on every draw, so turn it back off with {@link #attribute(boolean)} as soon as
	 * the window of interest has passed.
	 */
	public static void attribute(){
		attributing = true;
		bySite.clear();
		siteLog.clear();
	}

	public static void attribute( boolean on ){
		attributing = on;
	}

	public static boolean attributing(){
		return attributing;
	}

	/** Draw count per calling site, first-seen first. */
	public static java.util.Map< String, Integer > sites(){
		return bySite;
	}

	/** Every attributed draw site in draw order. Empty unless {@link #attribute()} is on. */
	public static java.util.List< String > siteLog(){
		return siteLog;
	}

	/** Every generator stack event in order. Empty unless counting is on. */
	public static java.util.List< String > generatorLog(){
		return generatorLog;
	}

	/**
	 * Records one generator stack event and the base stream's position at that moment.
	 *
	 * <p>Called by {@link Random} on every push, pop, reseed and reset, so a diagnostic can say where
	 * the base generator stood when a floor's own generator was pushed - which is the one piece of state
	 * outside that generator that a floor's layout can depend on.
	 *
	 * @param kind   what happened: push, pop, reseed or reset
	 * @param depth  generator stack depth after the event
	 * @param detail the seed pushed, or the seed the base was reseeded with, or empty
	 */
	public static void generator( String kind, int depth, String detail ){
		if (!enabled) return;
		generatorLog.add( kind + " depth=" + depth + " " + detail
				+ " baseDraws=" + baseDraws + " baseFingerprint=" + baseFingerprint
				+ " from=" + callerOutsideRandom() );
	}

	/** The tally as lines of {@code count<TAB>site}, most frequent first. */
	public static java.util.List< String > siteReport(){
		java.util.List< java.util.Map.Entry< String, Integer > > entries =
				new java.util.ArrayList<>( bySite.entrySet() );
		entries.sort( (a, b) -> b.getValue() - a.getValue() );

		java.util.List< String > lines = new java.util.ArrayList<>();
		lines.add( "# draws-by-site\tcount\tsite" );
		for (java.util.Map.Entry< String, Integer > e : entries){
			lines.add( e.getValue() + "\t" + e.getKey() );
		}
		return lines;
	}

	/**
	 * Records one draw against the nearest frame outside the RNG plumbing.
	 *
	 * <p>Both this class and {@link Random} are plumbing: {@code Random.Float(max)} calls
	 * {@code Float()} calls {@code Float(boolean)} calls {@code record}, so the immediate caller is
	 * always an RNG method and naming it would point at the plumbing rather than at the code that wanted
	 * randomness. Walking past both leaves the first frame that actually made a decision, which is the
	 * one worth naming and the one worth fixing.
	 */
	private static void attributeDraw(){
		StackTraceElement[] stack = new Throwable().getStackTrace();

		String self = RandomTrace.class.getName();
		for (int i = 2; i < stack.length; i++){
			String cls = stack[i].getClassName();
			if (cls.equals( self ) || cls.equals( Random.class.getName() )) continue;
			String site = frame( stack, i );
			bySite.merge( site, 1, Integer::sum );
			siteLog.add( site );
			return;
		}

		bySite.merge( "(no caller outside the RNG)", 1, Integer::sum );
		siteLog.add( "(no caller outside the RNG)" );
	}

	/** The first stack frame outside this class and {@link Random}, or a placeholder. */
	private static String callerOutsideRandom(){
		StackTraceElement[] stack = new Throwable().getStackTrace();

		String self = RandomTrace.class.getName();
		for (int i = 2; i < stack.length; i++){
			String cls = stack[i].getClassName();
			if (cls.equals( self ) || cls.equals( Random.class.getName() )) continue;
			return frame( stack, i );
		}

		return "(no caller outside the RNG)";
	}

	private static String frame( StackTraceElement[] stack, int index ){
		if (index < 0 || index >= stack.length) return "(no frame)";
		StackTraceElement f = stack[ index ];
		return f.getClassName() + "." + f.getMethodName()
				+ (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
	}

	public static long draws(){
		return draws;
	}

	public static long baseDraws(){
		return baseDraws;
	}

	public static long fingerprint(){
		return fingerprint;
	}

	/** Fingerprint of base-generator draws only. See {@link #baseFingerprint}. */
	public static long baseFingerprint(){
		return baseFingerprint;
	}

	/** One-line summary for assertions and diagnostics. */
	public static String summary(){
		return "draws=" + draws + " baseDraws=" + baseDraws + " fingerprint=" + fingerprint;
	}
}