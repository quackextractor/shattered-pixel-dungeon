package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.RunCommand;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
public class RolloutCheck {
	private static final int CHECKS = 7;
	private static final List< String > failures = new ArrayList<>();
	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-rolloutcheck" ));
		HeadlessServices.disableSaving( true );
		HeadlessGame.install();
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 120;
		check( () -> checkRandomSeedIsRecorded( config ) );
		check( () -> checkRandomSeedRecordingVerifies( config ) );
		check( () -> checkAnExplicitSeedIsRecordedVerbatim( config ) );
		check( () -> checkAnEmptySeedFlagIsRefusedNamingItself() );
		check( () -> checkAnAbsentSeedFlagIsAccepted() );
		check( () -> checkTheCommandSavesWhatItRecorded( config ) );
		check( () -> checkMaxTurnsReachesTheEnvironment( config ) );
		if (failures.isEmpty()){
			System.out.println( "[OK]     rollout recording: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  rollout recording: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
	private static void check( Runnable body ){
		body.run();
	}
	private static ScriptedEpisode.Result randomEpisode( EnvConfig config ){
		return ScriptedEpisode.record( config, "", HeroClass.WARRIOR, 60, 3 );
	}
	private static void checkRandomSeedIsRecorded( EnvConfig config ){
		ScriptedEpisode.Result episode = randomEpisode( config );
		String recorded = episode.replay.seedText;
		if ( recorded == null || recorded.trim().isEmpty() ){
			fail( "an episode asked for no seed recorded none, so verify would reset onto a different "
					+ "draw and diverge at step 0" );
			return;
		}
		if ( !recorded.equals( episode.seedText() )){
			fail( "the recording names seed [" + recorded + "] but the environment resolved ["
					+ episode.seedText() + "]" );
		}
	}
	private static void checkRandomSeedRecordingVerifies( EnvConfig config ){
		ScriptedEpisode.Result episode = randomEpisode( config );
		File file = new File( System.getProperty( "java.io.tmpdir" ), "rolloutcheck-random.replay" );
		try {
			ReplayIO.write( episode.replay, file );
		} catch (java.io.IOException e){
			fail( "could not write the fixture: " + e );
			return;
		}
		Replay read;
		try {
			read = ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( "could not read back the fixture: " + e );
			return;
		}
		if ( read.seedText.trim().isEmpty() ){
			fail( "a random-seed recording does not carry its seed through the file" );
			return;
		}
		EnvConfig replayed = ReplayIO.configFor( read );
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv verifyEnv =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						replayed, HeadlessGame.install() );
		ReplayIO.Verification result = ReplayIO.verify( read, verifyEnv );
		if ( result.diverged ){
			fail( "a recording made from a random-seed episode does not verify: " + result.divergence
					+ " at step " + result.divergedAt );
		}
		if ( result.stepsVerified != read.steps.size() ){
			fail( "verification covered " + result.stepsVerified + " of " + read.steps.size()
					+ " steps; a short match is not a reproduction" );
		}
	}
	private static void checkAnExplicitSeedIsRecordedVerbatim( EnvConfig config ){
		ScriptedEpisode.Result episode =
				ScriptedEpisode.record( config, "ROLLOUTCHECK-A", HeroClass.WARRIOR, 60, 3 );
		if ( !episode.replay.seedText.equals( "ROLLOUTCHECK-A" )){
			fail( "an episode asked for seed ROLLOUTCHECK-A recorded [" + episode.replay.seedText
					+ "] instead" );
		}
	}
	private static CliOptions cli( String... args ){
		CliOptions o = CliOptions.parse( args );
		o.noColor = true;
		return o;
	}
	private static void checkAnEmptySeedFlagIsRefusedNamingItself(){
		CliOptions options = cli( "--seed", "", "--max-turns", "40" );
		RunCommand.Result result = RunCommand.execute( options );
		if ( !result.refused() ){
			fail( "'--seed' with nothing after it was accepted. An empty seed and an absent flag are"
					+ " different requests - one is a mistake and one is a random seed - and a guard that"
					+ " refuses both makes a random run unreachable." );
			return;
		}
		if ( result.refusal == null || !result.refusal.contains( "--seed" )){
			fail( "the refusal for an empty --seed did not name the flag: \"" + result.refusal
					+ "\". A message that does not name it leaves the caller to guess which of eight"
					+ " options was wrong." );
		}
	}
	private static void checkAnAbsentSeedFlagIsAccepted(){
		CliOptions options = cli( "--max-turns", "40" );
		RunCommand.Result result = RunCommand.execute( options );
		if ( result.refused() ){
			fail( "a run with no --seed at all was refused (" + result.refusal
					+ "). Omitting the flag is how a random seed is asked for." );
			return;
		}
		if ( result.seedText == null || result.seedText.trim().isEmpty() ){
			fail( "a run with no --seed resolved to no seed, so its recording could not be verified" );
			return;
		}
		if ( result.steps <= 0 ){
			fail( "the command reported " + result.steps + " steps for a run it did not refuse" );
		}
	}
	private static void checkTheCommandSavesWhatItRecorded( EnvConfig config ){
		File file = new File( System.getProperty( "java.io.tmpdir" ), "rolloutcheck-command.replay" );
		if ( file.exists() && !file.delete()){
			fail( "could not clear " + file + " before writing the fixture" );
			return;
		}
		RunCommand.Result result = RunCommand.execute( cli(
				"--seed", "ROLLOUTCHECK-CMD", "--max-turns", "60", "--save", file.getPath() ));
		if ( result.refused() ){
			fail( "rollout with --save was refused: " + result.refusal );
			return;
		}
		if ( !file.exists() ){
			fail( "rollout with --save " + file + " wrote nothing" );
			return;
		}
		Replay read;
		try {
			read = ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( "the recording the command wrote could not be read back: " + e );
			return;
		}
		if ( read.steps.size() != result.steps ){
			fail( "the command reported " + result.steps + " steps and wrote " + read.steps.size()
					+ ", so the number it prints is not the recording a reader would watch" );
			return;
		}
		if ( !read.seedText.equals( result.seedText )){
			fail( "the command reported seed [" + result.seedText + "] and the file carries ["
					+ read.seedText + "]" );
		}
		EnvConfig replayed = ReplayIO.configFor( read );
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv verifyEnv =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						replayed, HeadlessGame.install() );
		ReplayIO.Verification verification = ReplayIO.verify( read, verifyEnv );
		if ( !verification.passed() ){
			fail( "the recording the rollout command wrote does not reproduce: "
					+ ( verification.diverged ? "diverged at step " + verification.divergedAt + " - "
						+ verification.divergence : verification.unwatchable ) );
		}
	}
	private static void checkMaxTurnsReachesTheEnvironment( EnvConfig config ){
		RunCommand.Result result = RunCommand.execute( cli(
				"--seed", "ROLLOUTCHECK-TURNS", "--max-turns", "40" ));
		if ( result.refused() ){
			fail( "rollout with --max-turns was refused: " + result.refusal );
			return;
		}
		if ( result.turns > 40 * 4 ){
			fail( "--max-turns 40 produced " + result.turns + " engine turns. The flag overrides"
					+ " env.turn_limit_per_floor, and a flag that does not reach the environment is a"
					+ " knob that does nothing - which is what issues.md 30 found for twenty of the"
					+ " thirty settings that were on the wire but not in the worker." );
		}
	}
	private static void fail( String message ){
		failures.add( message );
	}
}
