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
			"# spd-rng-trace v2\tstep\tbaseDraws\tbaseFingerprint";

	private final List< String > lines = new ArrayList<>();

	public RngTrace(){
		lines.add( HEADER );
	}

	/**
	 * Starts from zero once the world is built.
	 *
	 * <p>Implemented as {@link ReplayIO.StepObserver#onReset()} rather than left to the caller, because
	 * the caller cannot get between the world being built and the first step being replayed.
	 */
	public void onReset(){
		RandomTrace.reset();
	}

	/**
	 * Records the current cumulative base-generator counters.
	 *
	 * <p>Base-generator draws only, deliberately. A pushed generator is an independent stream that
	 * cannot influence the simulation - {@code Dungeon.seedForDepth} derives a per-depth seed on one and
	 * discards it - and including those draws reported a divergence where there was none. The rendered
	 * viewer asks for one more per-depth seed than the headless path, and folding that in made a
	 * byte-identical simulation look different.
	 */
	public void sample( int step ){
		lines.add( step + "\t" + RandomTrace.baseDraws() + "\t" + RandomTrace.baseFingerprint() );
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