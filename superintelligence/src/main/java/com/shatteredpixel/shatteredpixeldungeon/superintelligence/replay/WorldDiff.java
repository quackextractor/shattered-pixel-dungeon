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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two {@link WorldSnapshot} files field by field and names the first difference.
 *
 * <p>Returns a single string, empty when the two agree, matching {@link RngTrace#compare} so that every
 * two-sided comparison in this module reports the same way and callers handle them identically.
 *
 * <p>Coarser than {@code RngTrace.compare}, deliberately. That one compares whole records per step and
 * cannot say <i>which</i> field differed; this one resolves it down to the field and, for a cell, the
 * coordinates. A diff that stops at "step 17 differs" saves an investigator nothing.
 */
public class WorldDiff {

	private WorldDiff(){
	}

	/**
	 * @param golden the expected snapshot
	 * @param actual the run under test
	 * @return empty when they agree, otherwise a message naming the first difference found
	 */
	public static String compare( Path golden, WorldSnapshot actual ){
		Map< String, Map< String, List< String > > > expected;
		try {
			expected = WorldSnapshot.parse( Files.readAllLines( golden, StandardCharsets.UTF_8 ) );
		} catch (IOException e){
			return "cannot read the expected snapshot " + golden + " (" + e.getClass().getSimpleName() + ")";
		}

		Map< String, Map< String, List< String > > > got = WorldSnapshot.parse( actual.lines() );

		if (expected.isEmpty()) return "the expected snapshot " + golden + " has no records";

		for (Map.Entry< String, Map< String, List< String > > > e : expected.entrySet()){
			Map< String, List< String > > mine = got.get( e.getKey() );
			if (mine == null){
				return "no record for " + e.getKey() + ", which the expected snapshot has. It stopped early; "
						+ "expected has " + expected.size() + " records and this run has " + got.size() + ".";
			}
			String diff = compareRecords( e.getKey(), e.getValue(), mine );
			if (!diff.isEmpty()) return diff;
		}

		for (String label : got.keySet()){
			if (!expected.containsKey( label )){
				return "a record for " + label + ", which the expected snapshot does not have; "
						+ "this run went further than the expected one did";
			}
		}

		return "";
	}

	/** One label's worth of records: hero, roster, and one entry per described cell. */
	private static String compareRecords( String label,
			Map< String, List< String > > expected,
			Map< String, List< String > > got ){

		for (Map.Entry< String, List< String > > e : expected.entrySet()){
			List< String > mine = got.get( e.getKey() );
			if (mine == null){
				return "at " + label + ": no " + e.getKey() + " record, which the expected snapshot has. "
						+ "The run produced " + got.size() + " of the expected " + expected.size() + ".";
			}
			String diff = compareFields( label, e.getKey(), e.getValue(), mine );
			if (!diff.isEmpty()) return diff;
		}

		for (String name : got.keySet()){
			if (!expected.containsKey( name )){
				return "at " + label + ": a " + name + " record the expected snapshot does not have";
			}
		}

		return "";
	}

	/**
	 * Fields are {@code key=value} tokens, compared by key rather than by position.
	 *
	 * <p>By key because the field set is not guaranteed identical between two builds of the same code,
	 * and a positional comparison would report a cascade of differences from one added field instead of
	 * the one that actually changed.
	 */
	private static String compareFields( String label, String name,
			List< String > expected, List< String > got ){

		//A roster or cell record repeats its key across many rows, so rows are matched in order and only
		//the field that differs inside a matched row is reported.
		if (expected.size() == 1 && got.size() == 1){
			return compareOneRow( label, name, expected.get( 0 ), got.get( 0 ) );
		}

		int n = Math.max( expected.size(), got.size() );
		for (int i = 0; i < n; i++){
			if (i >= got.size()){
				return "at " + label + ": " + name + " #" + i + " exists in the expected snapshot and not here\n"
						+ "  expected: " + expected.get( i );
			}
			if (i >= expected.size()){
				return "at " + label + ": " + name + " #" + i + " exists here and not in the expected snapshot\n"
						+ "  actual: " + got.get( i );
			}
			String diff = compareOneRow( label, name + " #" + i, expected.get( i ), got.get( i ) );
			if (!diff.isEmpty()) return diff;
		}

		return "";
	}

	private static String compareOneRow( String label, String name, String expected, String actual ){
		Map< String, String > a = fields( expected );
		Map< String, String > b = fields( actual );

		for (Map.Entry< String, String > e : a.entrySet()){
			String mine = b.get( e.getKey() );
			if (mine == null){
				return "at " + label + ": " + name + " has no field " + e.getKey()
						+ ", which the expected snapshot has";
			}
			if (!e.getValue().equals( mine )){
				return "at " + label + ": " + name + " field " + e.getKey()
						+ "\n  expected: " + e.getValue()
						+ "\n  actual:   " + mine;
			}
		}
		for (String key : b.keySet()){
			if (!a.containsKey( key )){
				return "at " + label + ": " + name + " has field " + key
						+ ", which the expected snapshot does not have";
			}
		}

		return "";
	}

	/**
	 * Splits a record into {@code key -> value}, keyed so order is not compared.
	 *
	 * <p>On tab <i>and</i> space, and that is a fix rather than a preference. Record rows are not
	 * internally consistent: the mob, cell, gate and clock rows join their fields with tabs, while
	 * {@link WorldSnapshot}'s hero row joins them with spaces - {@code pos=684 hp=20/20 turn=11.0}.
	 * Splitting on tabs alone therefore made every hero record a single key, so a comparison of the hero
	 * - the one record that carries position, health and engine time, and the one every divergence is
	 * decided on - compared the whole row and reported the failure as a difference in whichever field
	 * happened to come first, always {@code pos}. It never named the field that actually differed.
	 *
	 * <p>Measured, and it changed the answer: on warrior-long the headless and rendered runs differ at
	 * step 13 in exactly one hero field, and the tool reported {@code hero field pos} for a position
	 * that was identical in both.
	 *
	 * <p>Splitting on space costs nothing elsewhere. The only record that uses spaces is the hero's
	 * inventory, where each item is a self-contained descriptor with no space inside it, so the items
	 * become individual keys - {@code weapon=...}, {@code armor=...}, {@code backpack/0=...} - which is a
	 * finer comparison than the single {@code inv=...} blob it replaces.
	 */
	private static Map< String, String > fields( String row ){
		Map< String, String > out = new LinkedHashMap<>();

		for (String token : row.split( "[ \t]+" )){
			int eq = token.indexOf( '=' );
			if (eq > 0) out.put( token.substring( 0, eq ), token.substring( eq + 1 ) );
		}

		return out;
	}

	/**
	 * Every record whose hero or roster differs from the one before it, within a single snapshot.
	 *
	 * <p>This is the question a two-sided diff cannot answer and the reason frame records exist: a world
	 * change on a frame that applied no recorded step appears in neither side's step sequence, so
	 * comparing step 16 against step 16 will never show it. Within one run the change is visible as a
	 * record that differs from its predecessor.
	 *
	 * @param kind {@code frame} to inspect frames, {@code step} for settled steps
	 */
	public static List< String > changedRecords( WorldSnapshot snapshot, String kind ){
		List< String > out = new ArrayList<>();
		Map< String, Map< String, List< String > > > parsed = WorldSnapshot.parse( snapshot.lines() );

		String previous = null;
		for (Map.Entry< String, Map< String, List< String > > > e : parsed.entrySet()){
			if (!e.getKey().startsWith( kind + ":" )) continue;

			StringBuilder joined = new StringBuilder();
			for (List< String > rows : e.getValue().values()){
				for (String r : rows) joined.append( r ).append( '\n' );
			}

			if (previous != null && !previous.contentEquals( joined )) out.add( e.getKey() );
			previous = joined.toString();
		}

		return out;
	}
}