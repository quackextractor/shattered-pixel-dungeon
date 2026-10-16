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

public class WorldDiff {

	private WorldDiff(){
	}

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

	private static boolean oneSidedOnly( String name ){
		return "accepted".equals( name ) || name.startsWith( "cell" );
	}

	private static String compareRecords( String label,
			Map< String, List< String > > expected,
			Map< String, List< String > > got ){

		for (String name : expected.keySet()){
			if (oneSidedOnly( name )) continue;
			List< String > mine = got.get( name );
			if (mine == null){
				return "at " + label + ": no " + name + " record, which the expected snapshot has. "
						+ "The run produced " + got.size() + " of the expected " + expected.size() + ".";
			}
			String diff = compareFields( label, name, expected.get( name ), mine );
			if (!diff.isEmpty()) return diff;
		}

		for (String name : got.keySet()){
			if (oneSidedOnly( name )) continue;
			if (!expected.containsKey( name )){
				return "at " + label + ": a " + name + " record the expected snapshot does not have";
			}
		}

		return "";
	}

	private static String compareFields( String label, String name,
			List< String > expected, List< String > got ){

		if (expected.size() == 1 && got.size() == 1){
			return compareOneRow( label, name, expected.get( 0 ), got.get( 0 ));
		}

		if (expected.size() == 1 && got.size() > 1){
			return compareOneRow( label, name + " (settled)", expected.get( 0 ),
					got.get( got.size() - 1 ));
		}

		if (expected.size() != got.size()){
			return "at " + label + ": " + name + " is recorded " + got.size() + " time(s) here and "
					+ expected.size() + " in the expected snapshot, so they cannot be compared row for"
					+ " row, and neither side holds a single settled record.";
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

	private static Map< String, String > fields( String row ){
		Map< String, String > out = new LinkedHashMap<>();

		for (String token : row.split( "[ \t]+" )){
			int eq = token.indexOf( '=' );
			if (eq > 0) out.put( token.substring( 0, eq ), token.substring( eq + 1 ) );
		}

		return out;
	}

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