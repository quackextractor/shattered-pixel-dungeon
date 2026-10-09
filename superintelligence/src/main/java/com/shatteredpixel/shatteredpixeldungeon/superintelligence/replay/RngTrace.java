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

package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.watabou.utils.RandomTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-step record of how much randomness the simulation consumed, for comparing a headless run against
 * the rendered game.
 *
 * <p>Everything else in the verification suite compares a headless run with another headless run, so a
 * fault in the headless path cannot show up. This compares against something produced by the rendered
 * game instead, which is the only artefact that can disagree.
 *
 * <p>Both are cumulative, so the first differing line localises the first step at which the two streams
 * part. A count alone would not be enough - two runs can draw the same number of values in a different
 * order and be just as broken - so the order-sensitive fingerprint travels with it.
 *
 * <p>Deliberately a diagnostic rather than a gate. Every engine change that alters a draw count or a draw
 * order invalidates a golden, and a gate people regenerate reflexively is worth less than one they read.
 * See PLAN-replay-verification.md section 10, open decision 1.
 */
public class RngTrace {

	private static final String HEADER =
			"# spd-rng-trace v3\tstep\tbaseDraws\tbaseFingerprint\tsites";

	private final List< String > lines = new ArrayList<>();

	/**
	 * The global site tally as it stood after the previous step, so each step's sites can be a delta.
	 *
	 * <p>A per-step diff of two runs that stop at different steps is meaningless if compared as totals:
	 * the run that halted early did less work, so every later site looks "missing". Deltas against the
	 * previous step make two traces comparable up to the shorter of them, which is the only span on which
	 * they can honestly be compared.
	 */
	private final java.util.Map< String, Integer > previousSites = new java.util.HashMap<>();

	public RngTrace(){
		lines.add( HEADER );
	}

	/**
	 * Records the numeric run seed in the trace header.
	 *
	 * <p>Put there because two paths can draw at the same sites in the same order and still get
	 * different values, and the only thing that explains that is the base generator starting from a
	 * different place. The seed text is not enough to compare: it goes through
	 * {@code SPDSettings.customSeed} on one side and straight to the level pipeline on the other, so
	 * "the same recording" does not by itself mean "the same seed was applied".
	 */
	public void dungeonSeed( long seed ){
		lines.add( "# dungeonSeed=" + seed );
	}

	/**
	 * Starts from zero once the world is built.
	 *
	 * <p>Implemented as {@link ReplayIO.StepObserver#onReset()} rather than left to the caller, because
	 * the caller cannot get between the world being built and the first step being replayed.
	 */
	public void onReset(){
		//What generation left behind, recorded before the counters are zeroed. Every recorded step shows
		//zero base draws, so the entire windowed-versus-headless difference has to be settled here or it
		//is not settled at all: if these two agree, generation reproduced and the offset was introduced
		//afterwards, and if they differ then the two paths never built the same base stream to begin with.
		generationBaseDraws = RandomTrace.baseDraws();
		generationBaseFingerprint = RandomTrace.baseFingerprint();

		RandomTrace.reset();
		//The current tally becomes the baseline rather than being cleared. Attribution is switched on
		//before the world is built, so clearing would make step 0's delta the entire generation - several
		//thousand draws that have nothing to do with the first recorded step, and that every step would
		//then be compared against.
		previousSites.clear();
		previousSites.putAll( RandomTrace.sites() );

		//The generation window's own tally goes into the trace as a header line, because it is the one
		//part of the run that is deliberately excluded from the per-step comparison and so has nowhere
		//else to be looked at.
		//
		//It is also where a fault of this class hides. Level generation is the largest single consumer of
		//randomness in a run, and if the two environments draw different amounts of it the gameplay stream
		//starts in a different place - which then shows up hundreds of steps later as a damage roll that
		//came out differently, with nothing at the divergence to say why. Written as "site=count" pairs so
		//two traces can be diffed on it directly.
		StringBuilder window = new StringBuilder();
		for (java.util.Map.Entry< String, Integer > e : RandomTrace.sites().entrySet()){
			if (e.getValue() <= 0) continue;
			if (window.length() > 0) window.append( ',' );
			window.append( e.getKey() ).append( '=' ).append( e.getValue() );
		}
		lines.add( "# generationSites=" + window );

		//Read here, not by the caller before the run starts. Dungeon.seed is only set once the level
		//pipeline has run, so a caller that samples it earlier reads whatever was there before - which is
		//zero in a fresh headless process, and looks exactly like a seeding fault when it is a measurement
		//one. onReset is called immediately after the environment reset, which is the first moment the
		//seed exists.
		dungeonSeed( com.shatteredpixel.shatteredpixeldungeon.Dungeon.seed );
		lines.add( "# generationBaseDraws=" + generationBaseDraws
				+ " generationBaseFingerprint=" + generationBaseFingerprint );
	}

	private long generationBaseDraws;
	private long generationBaseFingerprint;

	/**
	 * Records the current cumulative base-generator counters, and the sites that drew since the last
	 * sample.
	 *
	 * <p>The sites column is the per-step attribution, which is what makes a divergence actionable rather
	 * than merely detectable: "first divergence at step 6" says where, and the two site sets on that line
	 * say what each path did instead.
	 *
	 * <p>Sorted by site name, not left in first-seen order, because two runs that draw the same values in
	 * a different order must still produce comparable lines - and first-seen order is exactly what
	 * differs when they do not.
	 */
	private String sitesThisStep(){
		java.util.Map< String, Integer > current = RandomTrace.sites();
		java.util.List< String > parts = new ArrayList<>();

		for (java.util.Map.Entry< String, Integer > e : current.entrySet()){
			int delta = e.getValue() - previousSites.getOrDefault( e.getKey(), 0 );
			if (delta > 0) parts.add( e.getKey() + "=" + delta );
		}

		previousSites.clear();
		previousSites.putAll( current );

		java.util.Collections.sort( parts );
		return parts.isEmpty() ? "-" : String.join( ",", parts );
	}

/**
	 * Records the current cumulative base-generator counters, and the sites that drew since the last
	 * sample.
	 *
	 * <p>Base-generator draws only, deliberately. A pushed generator is an independent stream that cannot
	 * influence the simulation - {@code Dungeon.seedForDepth} derives a per-depth seed on one and discards
	 * it - and including those draws reported a divergence where there was none. The rendered viewer asks
	 * for one more per-depth seed than the headless path, and folding that in made a byte-identical
	 * simulation look divergent.
	 *
	 * <p>The sites column is the per-step attribution, which is what makes a divergence actionable rather
	 * than merely detectable: "first divergence at step 6" says where, and the two site sets on that line
	 * say what each path did instead. Totals would not: a run that halts early has simply done less work,
	 * so every later site looks missing. Deltas against the previous step make two traces comparable up to
	 * the shorter of them.
	 *
	 * <p>Sorted by site name rather than left in first-seen order, because first-seen order is precisely
	 * what differs when two runs draw the same values in a different order.
	 */
	public void sample( int step ){
		lines.add( step + "\t" + RandomTrace.baseDraws() + "\t" + RandomTrace.baseFingerprint()
				+ "\t" + sitesThisStep() );
	}

	/** {@link ReplayIO.StepObserver} view, so a trace can be passed straight to {@code verify}. */
	public ReplayIO.StepObserver observer(){
		return new ReplayIO.StepObserver() {
			@Override public void onReset(){ RngTrace.this.onReset(); }
			@Override public void onStep( int index, Replay.Step step, boolean running ){
				sample( index );
			}
		};
	}

	public int size(){
		return lines.size() - 1;
	}

	public String render(){
		return String.join( "\n", lines ) + "\n";
	}

	public void writeTo( Path path ) throws IOException {
		Files.write( path, render().getBytes( StandardCharsets.UTF_8 ) );
	}

	/**
	 * Compares this trace against a golden one.
	 *
	 * <p>Keyed by step rather than by line position. Comparing positions works right up until one run
	 * produces a different number of samples, at which point every subsequent label is shifted and the
	 * report blames the wrong step. Keying by the step label keeps "first divergence" meaning what it
	 * says, and catches a run that stops early or carries on further as what they are.
	 *
	 * @return empty when they agree, otherwise a message naming the first differing step
	 */
	public static String compare( Path golden, RngTrace actual ){
		java.util.LinkedHashMap< String, String > expected;
		try {
			expected = parse( Files.readAllLines( golden, StandardCharsets.UTF_8 ) );
		} catch (IOException e){
			return "cannot read the golden trace " + golden
					+ " (" + e.getClass().getSimpleName() + ")";
		}

		java.util.LinkedHashMap< String, String > got = parse( actual.lines );

		if (expected.isEmpty()){
			return "golden trace " + golden + " has no samples";
		}

		for (java.util.Map.Entry< String, String > e : expected.entrySet()){
			String mine = got.get( e.getKey() );
			if (mine == null){
				return "this run produced no trace for step " + e.getKey()
						+ ", which the golden trace has. It stopped early; the golden has "
						+ expected.size() + " samples and this run has " + got.size() + ".";
			}
			if (!e.getValue().equals( mine )){
				return "first divergence at step " + e.getKey() + ":\n"
						+ "  golden: " + e.getValue() + "\n"
						+ "  actual: " + mine;
			}
		}

		for (String label : got.keySet()){
			if (!expected.containsKey( label )){
				return "this run produced a trace for step " + label
						+ ", which the golden trace does not have; it ran further than the recording did";
			}
		}

		return "";
	}

	/** Step label to "draws\tfingerprint". */
	private static java.util.LinkedHashMap< String, String > parse( List< String > lines ){
		java.util.LinkedHashMap< String, String > out = new java.util.LinkedHashMap<>();
		for (String line : lines){
			if (line == null) continue;
			line = line.trim();
			if (line.isEmpty() || line.startsWith( "#" )) continue;
			int tab = line.indexOf( '\t' );
			if (tab < 0) continue;
			out.put( line.substring( 0, tab ), line.substring( tab + 1 ) );
		}
		return out;
	}
}