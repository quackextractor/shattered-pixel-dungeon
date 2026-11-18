package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.Coverage;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ReachExpectations;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.Scenario;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioCatalog;

import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;

public class CoverageCheck {

	public static void main( String[] args ){
		List< Scenario > scenarios = ScenarioCatalog.all();
		SortedSet< String > denominator = Coverage.denominator();
		SortedSet< String > declared = Coverage.declared( scenarios );

		if (List.of( args ).contains( "--print-missing" )){
			for (String key : denominator) if (!declared.contains( key )) System.out.println( key );
			System.exit( 0 );
		}

		checkTheScanKeepsWhatItShould();
		checkTheEnumsAreAllThere();
		checkMissingNamesTheUndeclared();

		SortedMap< String, String > excluded = Coverage.excluded();
		SortedSet< String > universe = Coverage.universe();

		for (String key : Coverage.missing( denominator, declared )) GateReport.fail( "uncovered: " + key );
		SortedSet< String > named = new TreeSet<>( declared );
		named.addAll( excluded.keySet() );
		for (String key : named) if (!universe.contains( key )) GateReport.fail( "unknown key: " + key );
		for (String key : excluded.keySet()){
			if (declared.contains( key )) GateReport.fail( "excluded and declared: " + key );
		}
		for (Scenario scenario : scenarios){
			if (scenario.covers.isEmpty()) continue;
			if (!scenario.judgeReach || ReachExpectations.expected( scenario.id ) != null
					|| ReachExpectations.knownFailure( scenario.id ) != null){
				GateReport.fail( scenario.id + " declares covers but is not judged for reach" );
			}
		}

		if (GateReport.failureCount() > 0){
			System.out.println( "[ERROR]  coverage: " + GateReport.failureCount() + " failure(s)" );
			for (String failure : GateReport.failures()) System.out.println( "        " + failure );
			System.exit( 1 );
		}
		SortedSet< String > covered = new TreeSet<>( declared );
		covered.retainAll( denominator );
		System.out.println( "[OK]     coverage: " + covered.size() + " of " + denominator.size() + " covered, "
				+ excluded.size() + " excluded" );
		System.exit( 0 );
	}

	private static void checkTheScanKeepsWhatItShould(){
		SortedSet< String > scanned = Coverage.scanned();
		List< String > kept = List.of( "actors.mobs.Rat", "actors.mobs.Shaman$RedShaman",
				"items.potions.brews.BlizzardBrew$Recipe", "plants.Icecap$Seed", "levels.SewerBossLevel",
				"actors.hero.spells.Smite" );
		List< String > dropped = List.of( "actors.mobs.Mob", "items.rings.RingOfMight$Might",
				"levels.rooms.standard.EmptyRoom" );
		int before = GateReport.failureCount();
		for (String key : kept) if (!scanned.contains( key )) GateReport.fail( "self-test: the scan lost " + key );
		for (String key : dropped) if (scanned.contains( key )) GateReport.fail( "self-test: the scan kept " + key );
		for (String key : scanned) if (key.contains( "$1" )) GateReport.fail( "self-test: the scan kept anonymous " + key );
		if (GateReport.failureCount() == before){
			System.out.println( "  the scan keeps " + kept.size() + " concrete classes and drops an abstract base, a nested"
					+ " buff, an unscanned room and every anonymous class, of " + scanned.size() );
		}
	}

	private static void checkTheEnumsAreAllThere(){
		SortedSet< String > enums = Coverage.enums();
		long talents = enums.stream().filter( k -> k.startsWith( "talent:" )).count();
		long subclasses = enums.stream().filter( k -> k.startsWith( "subclass:" )).count();
		long classes = enums.stream().filter( k -> k.startsWith( "heroclass:" )).count();
		if (talents != 160 || subclasses != 12 || classes != 6 || enums.contains( "subclass:NONE" )){
			GateReport.fail( "self-test: enums() holds " + talents + " talents, " + subclasses + " subclasses and " + classes
					+ " hero classes, subclass:NONE " + (enums.contains( "subclass:NONE" ) ? "in" : "out")
					+ "; expected 160, 12 and 6 with NONE out" );
		} else {
			System.out.println( "  enums() holds 160 talents, 12 subclasses without NONE and 6 hero classes" );
		}
	}

	private static void checkMissingNamesTheUndeclared(){
		Set< String > missing = Coverage.missing( Set.of( "actors.mobs.Rat", "actors.mobs.Bat" ),
				Set.of( "actors.mobs.Bat" ));
		if (!missing.equals( Set.of( "actors.mobs.Rat" ))){
			GateReport.fail( "self-test: missing() over {Rat, Bat} with Bat declared returned " + missing + ", not [actors.mobs.Rat]" );
		} else {
			System.out.println( "  missing() names the undeclared key and only it" );
		}
	}
}
