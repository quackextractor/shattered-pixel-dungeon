package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public class ParityCheck {
	private static final String[] DEFAULT_SEEDS = {
			"PARITY-ALPHA", "PARITY-BRAVO", "PARITY-CHARLIE", "PARITY-DELTA",
			"PARITY-ECHO", "PARITY-FOXTROT", "PARITY-GOLF", "PARITY-HOTEL"
	};
	private static final HeroClass[] DEFAULT_HEROES = {
			HeroClass.WARRIOR, HeroClass.MAGE, HeroClass.HUNTRESS, HeroClass.ROGUE
	};
	private static int turnLimit = 400;
	private static final List<String> failures = new ArrayList<>();
	private static File outDir;
	public static void main( String[] args ) throws IOException {
		List<String> seeds = new ArrayList<>();
		List<HeroClass> heroes = new ArrayList<>();
		boolean full = false;
		for (int i = 0; i < args.length; i++){
			switch (args[ i ]) {
				case "--seeds":
					int n = Integer.parseInt( args[ ++i ] );
					for (int s = 0; s < n && s < DEFAULT_SEEDS.length; s++){
						seeds.add( DEFAULT_SEEDS[ s ] );
					}
					break;
				case "--heroes":
					int h = Integer.parseInt( args[ ++i ] );
					for (int k = 0; k < h && k < DEFAULT_HEROES.length; k++){
						heroes.add( DEFAULT_HEROES[ k ] );
					}
					break;
				case "--turns":
					turnLimit = Integer.parseInt( args[ ++i ] );
					break;
				case "--full":
					full = true;
					break;
				default:
					System.err.println( "[WARN] unknown option: " + args[ i ] );
			}
		}
		if (seeds.isEmpty()){
			for (String s : DEFAULT_SEEDS) seeds.add( s );
		}
		if (heroes.isEmpty()){
			for (HeroClass h : DEFAULT_HEROES) heroes.add( h );
		}
		if (full){
			turnLimit = 1500;
		}
		outDir = Scratch.dir( "paritycheck" );
		outDir.mkdirs();
		HeadlessServices.install( outDir );
		HeadlessServices.disableSaving( true );
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = turnLimit;
		System.out.println( "parity sweep: " + seeds.size() + " seed(s) x " + heroes.size()
				+ " hero(es), " + turnLimit + " turns, one process" );
		List< Case > recorded = new ArrayList<>();
		for (String seed : seeds){
			for (HeroClass hero : heroes){
				Case c = recordAndVerify( config, seed, hero );
				recorded.add( c );
			}
		}
		List<String> secondPass = new ArrayList<>();
		for (Case c : recorded){
			ReplayIO.Verification again = verify( c.replay );
			if (again.diverged){
				secondPass.add( c.name() + " diverged on re-verification at step " + again.divergedAt
						+ ": " + again.divergence + " (it verified cleanly the first time, so state from the"
						+ " other runs reached it)" );
			}
		}
		report( recorded, secondPass );
		if (failures.isEmpty()){
			System.out.println( "[OK]     replay parity: " + recorded.size() + " recording(s) over "
					+ seeds.size() + " seed(s) replayed exactly, twice, in one process" );
		} else {
			System.out.println( "[ERROR]  replay parity: " + failures.size()
					+ " recording(s) did not replay exactly" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
	private static class Case {
		Replay replay;
		final File file;
		final String seed;
		final HeroClass hero;
		int steps;
		final double recordedScore;
		double reproducedScore;
		int verifiedSteps;
		boolean diverged;
		int divergedAt = -1;
		String divergence = "";
		Case( Replay replay, File file, String seed, HeroClass hero, int steps, double recordedScore ){
			this.replay = replay;
			this.file = file;
			this.seed = seed;
			this.hero = hero;
			this.steps = steps;
			this.recordedScore = recordedScore;
		}
		String name(){
			return seed + "/" + hero;
		}
	}
	private static Case recordAndVerify( EnvConfig config, String seed, HeroClass hero ){
		ScriptedEpisode.Result episode = ScriptedEpisode.record( config, seed, hero, 0, seed.hashCode() );
		Replay replay = episode.replay;
		File file = new File( outDir, seed + "-" + hero.name() + ".replay" );
		Case c = new Case( replay, file, seed, hero, replay.length(), replay.score );
		try {
			ReplayIO.write( replay, file );
			Replay read = ReplayIO.read( file );
			c.replay = read;
			c.steps = read.length();
		} catch (IOException e){
			c.diverged = true;
			c.divergence = "could not write and re-read the recording (" + e.getClass().getSimpleName()
					+ ": " + e.getMessage() + ")";
			failures.add( c.name() + ": " + c.divergence );
			return c;
		}
		ReplayIO.Verification result = verify( c.replay );
		c.diverged = result.diverged;
		c.divergedAt = result.divergedAt;
		c.divergence = result.divergence;
		c.verifiedSteps = result.stepsVerified;
		c.reproducedScore = result.score;
		if (result.diverged){
			failures.add( c.name() + " (" + c.steps + " steps) diverged at step " + result.divergedAt
					+ " of " + c.steps + ": " + result.divergence + " - recording at "
					+ file.getAbsolutePath() );
		} else if (Math.abs( result.score - c.recordedScore ) > 1e-6){
			failures.add( c.name() + " replayed step for step but scored " + result.score
					+ " against a recorded " + c.recordedScore );
		} else if (result.stepsVerified < c.steps){
			failures.add( c.name() + " verified only " + result.stepsVerified + " of " + c.steps
					+ " recorded steps before the episode ended" );
		}
		return c;
	}
	private static ReplayIO.Verification verify( Replay replay ){
		EnvConfig config = ReplayIO.configFor( replay );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		return ReplayIO.verify( replay, env );
	}
	private static void report( List<Case > cases, List<String> secondPass ){
		System.out.println();
		System.out.printf( "  %-22s %-9s %6s %7s %10s  %s%n",
				"seed", "hero", "steps", "turns", "score", "verdict" );
		System.out.println( "  " + "-".repeat( 72 ));
		for (Case c : cases){
			String verdict = c.diverged
					? "DIVERGED at " + c.divergedAt + ": " + c.divergence
					: "reproduced (" + c.verifiedSteps + " steps, " + String.format( "%.3f", c.reproducedScore ) + ")";
			System.out.printf( "  %-22s %-9s %6d %7d %10s  %s%n",
					c.seed, c.hero, c.steps, c.replay.turns,
					String.format( "%.3f", c.recordedScore ), verdict );
		}
		if (!secondPass.isEmpty()) failures.addAll( secondPass );
		System.out.println();
		if (secondPass.isEmpty()){
			System.out.println( "  all " + cases.size() + " recordings also re-verified cleanly after the"
					+ " whole sweep had run in between them" );
		} else {
			System.out.println( "  " + secondPass.size() + " recording(s) failed only on re-verification,"
					+ " which is the signature of state surviving a reset:" );
			for (String s : secondPass) System.out.println( "        " + s );
		}
	}
}
