package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.ElementalStrike;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Projecting;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RunicBlade;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.Scenario;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioRunner;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioSetup;
import com.watabou.utils.Random;
import com.watabou.utils.RandomTrace;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ScenarioCheck {

	private static volatile String inFlight = "self-tests";

	public static void main( String[] args ){
		File out = new File( option( args, "--out", "out/scenarios" ));
		String only = option( args, "--only", null );
		long budget = Long.parseLong( option( args, "--budget-seconds", "120" ));
		String seeds = option( args, "--seeds", null );

		HeadlessServices.install( Scratch.dir( "scenariocheck" ));
		HeadlessServices.disableSaving( true );
		watchdog( budget );
		checkLoadingTheCatalogDrawsNothing();
		checkTheFilterMatchesListsAndPrefixes();

		List< Scenario > chosen = new ArrayList<>();
		for (Scenario scenario : ScenarioCatalog.all()){
			if (only == null || ScenarioCatalog.selected( only, scenario.id )) chosen.add( scenario );
		}
		if (chosen.isEmpty()){
			System.out.println( "[ERROR]  scenarios: no scenario matches " + only );
			System.exit( 1 );
		}
		if (seeds != null) for (Scenario scenario : chosen) scenario.seeds( searchSeeds( Integer.parseInt( seeds )));
		if (only == null) ScenarioReport.clean( out );

		checkASetupLeavesTheGameplayStreamAlone();
		checkAnUnwitnessedCoverFails();
		checkTheMaskChangesNoHeroState();
		checkARoomScenarioLeavesNoForcedQueue();

		ScenarioRunner runner = new ScenarioRunner( new EnvConfig(), out );
		List< ScenarioRunner.Result > results = new ArrayList<>();
		for (Scenario scenario : chosen){
			inFlight = scenario.id;
			results.add( runner.run( scenario ));
			if (scenario.dirtyEnd) ScenarioReport.leakCheck( results.get( results.size() - 1 ), results );
		}
		inFlight = "re-verification";

		checkABlankScenarioDoesNotVerify( results );
		ScenarioReport.reverify( results );
		ScenarioReport.write( out, results );

		System.exit( ScenarioReport.print( results, GateReport.failures() ));
	}

	private static void checkLoadingTheCatalogDrawsNothing(){
		boolean wasOn = RandomTrace.enabled();
		RandomTrace.reset();
		RandomTrace.enable();
		int scenarios = ScenarioCatalog.all().size();
		long draws = RandomTrace.draws();
		if (!wasOn) RandomTrace.disable();
		if (draws != 0){
			GateReport.fail( "self-test: loading the catalogue of " + scenarios + " scenarios drew " + draws + " values. The"
					+ " viewer loads it on its first step, after the floor is built, so a game object made at load time"
					+ " moves the viewer's stream and not the trainer's" );
		} else {
			System.out.println( "  loading the catalogue of " + scenarios + " scenarios draws nothing" );
		}
	}

	private static void checkASetupLeavesTheGameplayStreamAlone(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( "SCN-A", HeroClass.WARRIOR );
		Scenario drawing = new Scenario( "selftest.rng", "self-test", HeroClass.WARRIOR,
				( world, kit ) -> {
					for (int i = 0; i < 16; i++) Random.Int( 1000 );
				}, d -> {} );
		boolean wasOn = RandomTrace.enabled();
		RandomTrace.reset();
		RandomTrace.enable();
		ScenarioSetup.hookFor( drawing ).build();
		long base = RandomTrace.baseDraws(), all = RandomTrace.draws();
		if (!wasOn) RandomTrace.disable();
		if (all < 16){
			GateReport.fail( "self-test: a setup that draws 16 values recorded " + all + " draws, so the base-draw"
					+ " check below measured nothing" );
		} else if (base != 0){
			GateReport.fail( "self-test: a setup moved the gameplay stream by " + base + " base draws. Setup must run"
					+ " inside a pushed generator, or every step after it reads a different stream from the viewer's" );
		} else {
			System.out.println( "  a setup's " + all + " draws stay off the gameplay stream" );
		}
	}

	private static void checkTheFilterMatchesListsAndPrefixes(){
		String filter = "trap.*, melee";
		boolean right = ScenarioCatalog.selected( filter, "trap.Alarm" ) && ScenarioCatalog.selected( filter, "melee" )
				&& !ScenarioCatalog.selected( filter, "melee2" ) && !ScenarioCatalog.selected( filter, "wand.trap" );
		if (!right) GateReport.fail( "self-test: --only \"" + filter + "\" does not select exactly trap.Alarm and melee" );
	}

	private static String[] searchSeeds( int count ){
		String[] out = new String[ count ];
		for (int i = 0; i < count; i++) out[ i ] = "SCN-" + i;
		return out;
	}

	private static void checkAnUnwitnessedCoverFails(){
		ScenarioRunner runner = new ScenarioRunner( new EnvConfig(), null );
		Scenario scenario = ratScenario();
		ScenarioCatalog.registerSelfTest( scenario );
		ScenarioRunner.Result bat = runner.run( scenario.covers( Bat.class ));
		scenario.covers.clear();
		ScenarioRunner.Result rat = runner.run( scenario.covers( Rat.class ));
		String expected = "declared covers never witnessed: actors.mobs.Bat";
		if (!"FAIL".equals( bat.status ) || !bat.failure.contains( expected )){
			GateReport.fail( "self-test: a scenario that spawns a Rat and declares Bat ended " + bat.status + " ("
					+ bat.failure + "), not FAIL with \"" + expected + "\"" );
		} else if (!"OK".equals( rat.status )){
			GateReport.fail( "self-test: a scenario that spawns a Rat and declares Rat ended " + rat.status + ": " + rat.failure );
		} else {
			System.out.println( "  a declared cover the run never saw fails: " + bat.failure );
		}
	}

	private static void checkTheMaskChangesNoHeroState(){
		Scenario scenario = new Scenario( "selftest.mask", "self-test", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Weapon weapon = (Weapon) kit.hero().belongings.weapon;
					weapon.level( 30 );
					weapon.enchant( new Projecting() );
					Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ));
					rat.state = rat.PASSIVE;
					kit.buff( RunicBlade.RunicSlashTracker.class, 100f );
					kit.buff( ElementalStrike.DirectedPowerTracker.class, 100f );
				},
				d -> {
					d.env().mapper().actionMask( d.env().mode(), new float[ Action.size() ] );
					d.expect( Dungeon.hero.buff( RunicBlade.RunicSlashTracker.class ) != null
							&& Dungeon.hero.buff( ElementalStrike.DirectedPowerTracker.class ) != null,
							"building the action mask beside a Projecting reach target detached an ability tracker" );
				} );
		ScenarioCatalog.registerSelfTest( scenario );
		ScenarioRunner.Result result = new ScenarioRunner( new EnvConfig(), null ).run( scenario );
		if (!"OK".equals( result.status )){
			GateReport.fail( "self-test: " + result.failure );
		} else {
			System.out.println( "  building the action mask with a Projecting reach target keeps the ability trackers" );
		}
	}

	private static void checkARoomScenarioLeavesNoForcedQueue(){
		ScenarioRunner runner = new ScenarioRunner( new EnvConfig(), null );
		ScenarioRunner.Result room = runner.run( ScenarioCatalog.find( "room.ArmoryRoom" ));
		ScenarioRunner.Result melee = runner.run( ScenarioCatalog.find( "melee" ));
		List< Class< ? extends Room > > queue = SpecialRoom.runSpecials;
		if (new HashSet<>( queue ).size() != queue.size()){
			GateReport.fail( "self-test: after room.ArmoryRoom and melee, SpecialRoom.runSpecials still holds the forced"
					+ " queue " + queue + ", so every later floor generates the forced room" );
		} else if (!"OK".equals( room.status )){
			GateReport.fail( "self-test: room.ArmoryRoom ended " + room.status + " (" + room.failure + "), so the forced"
					+ " queue it should leave behind was never built" );
		} else if (!"OK".equals( melee.status )){
			GateReport.fail( "self-test: melee after room.ArmoryRoom ended " + melee.status + ": " + melee.failure );
		} else {
			System.out.println( "  melee after room.ArmoryRoom verifies on the run's own special-room queue" );
		}
	}

	private static Scenario ratScenario(){
		return new Scenario( "selftest.witness", "self-test", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.mob( new Rat(), world.adjacentFree() );
				}, d -> d.act( Action.WAIT ) );
	}

	private static void checkABlankScenarioDoesNotVerify( List< ScenarioRunner.Result > results ){
		for (ScenarioRunner.Result result : results){
			if (!"melee".equals( result.scenario.id ) || result.replay == null) continue;
			Replay replay = result.replay;
			String digest = replay.worldEnd;
			replay.worldEnd = digest.replaceFirst( "mobs:[0-9a-f]+", "mobs:0" );
			ReplayIO.Verification tampered = ScenarioRunner.verify( replay );
			replay.worldEnd = digest;
			if (tampered.passed() || !tampered.divergence.contains( "section mobs" )){
				GateReport.fail( "self-test: melee verified with its world_end mobs digest altered: "
						+ (tampered.passed() ? "passed" : tampered.divergence) );
			} else {
				System.out.println( "  melee with an altered world_end does not verify: " + tampered.divergence );
			}
			replay.scenario = "";
			ReplayIO.Verification verified = ScenarioRunner.verify( replay );
			replay.scenario = "melee";
			if (verified.passed()){
				GateReport.fail( "self-test: melee verified with its scenario= blanked, so verify never rebuilt"
						+ " the world and every scenario passes on the bare floor" );
			} else {
				System.out.println( "  melee with scenario= blanked does not verify: " + ScenarioRunner.describe( verified ));
			}
		}
	}

	private static void watchdog( long seconds ){
		Thread watchdog = new Thread( () -> {
			try {
				Thread.sleep( seconds * 1000L );
			} catch (InterruptedException e){
				return;
			}
			System.out.println( "[ERROR]  scenarios: still running after " + seconds + "s, in " + inFlight );
			System.out.flush();
			Runtime.getRuntime().halt( 1 );
		}, "scenario-watchdog" );
		watchdog.setDaemon( true );
		watchdog.start();
	}

	private static String option( String[] args, String name, String fallback ){
		for (int i = 0; i + 1 < args.length; i++) if (args[ i ].equals( name )) return args[ i + 1 ];
		return fallback;
	}
}
