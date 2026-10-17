package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.watabou.utils.RandomTrace;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records a run, verifies it repeatedly in one process, and names the draw site whose tally moved.
 *
 * <p>The question `issues.md` 37 and 18 could not answer is *which call site* spends the draws, and
 * `baseDraws` is cumulative so it cannot say. `RandomTrace.attribute()` is the instrument that can,
 * and what was missing was a reproduction to point it at. `paritycheck --seeds 1 --heroes 1` is one.
 *
 * <p>Run it twice. The first run's *first* verification is the baseline a fresh JVM produces; the
 * second run repeats the same work after an episode has run in the process, which is where the two
 * part.
 */
public class ReproCheck {

	private static final String SEED = "PARITY-ALPHA";
	private static final int TURNS = 400;

	public static void main( String[] args ) throws java.io.IOException {
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-reprocheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = TURNS;

		boolean baseline = args.length > 0 && args[ 0 ].equals( "baseline" );

		Replay recorded = null;
		if (!baseline){
			ScriptedEpisode.Result episode = ScriptedEpisode.record( config, SEED,
					HeroClass.WARRIOR, 0, SEED.hashCode() );
			recorded = episode.replay;
			File out = new File( new File( "." ).getAbsoluteFile(), SAVED );
			if (out.getParentFile() != null ) out.getParentFile().mkdirs();
			ReplayIO.write( recorded, out );
			System.out.println( "recorded " + recorded.length() + " steps as the control and to poison"
					+ " the process: " + out.getAbsolutePath() );
		}

		Replay replay = readSaved();
		if (replay == null){
			System.err.println( "[ERROR] no control recording at " + SAVED );
			System.exit( 1 );
			return;
		}

		System.out.println( (baseline ? "baseline" : "poisoned")
				+ ": verifying " + replay.length() + " steps, score " + replay.score );

		if (recorded != null){
			File out = new File( new File( "." ).getAbsoluteFile(), SAVED );
			if (out.getParentFile() != null ) out.getParentFile().mkdirs();
			ReplayIO.write( recorded, out );
			System.out.println( "wrote the control recording to " + out.getAbsolutePath() );
		}

		verify( config, replay, baseline ? "baseline" : "first" );
		verify( config, replay, baseline ? "baseline-again" : "second" );
	}


	private static final String SAVED =
			"out/evidence/issue-38-PARITY-ALPHA-WARRIOR.replay";


	private static Replay readSaved(){
		try {
			return ReplayIO.read( new File( new File( "." ).getAbsoluteFile(), SAVED ) );
		} catch (java.io.IOException e){
			return null;
		}
	}


private static void verify( EnvConfig config, Replay replay, String label ){
		RandomTrace.enable();
		RandomTrace.attribute();

		ReplayIO.Verification result = ReplayIO.verify( replay,
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						config, HeadlessGame.install() ) );

		Map< String, Integer > sites = new LinkedHashMap<>( RandomTrace.sites() );

		RandomTrace.attribute( false );
		RandomTrace.disable();

		System.out.println();
		System.out.println( "verify #" + label + ": diverged=" + result.diverged
				+ (result.diverged ? " at step " + result.divergedAt + " - " + result.divergence : "")
				+ ", steps " + result.stepsVerified + ", base draws " + RandomTrace.baseDraws()
				+ ", sites " + sites.size() );
		System.out.println( "  secrets per region: " + secretsPerRegion() );
		print( sites, label );
		dump( replay, label, sites );
	}


	private static String secretsPerRegion(){
		StringBuilder sb = new StringBuilder();
		try {
			Class<?> secret = Class.forName(
					"com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom" );
			java.lang.reflect.Field field = secret.getDeclaredField( "regionSecretsThisRun" );
			field.setAccessible( true );
			int[] values = (int[]) field.get( null );
			for (int i = 0; i < values.length; i++){
				if (i > 0) sb.append( ',' );
				sb.append( values[ i ] );
			}
		} catch (Exception e){
			return "(unreadable: " + e.getClass().getSimpleName() + ")";
		}
		return sb.toString();
	}


	private static void dump( Replay replay, String label, Map< String, Integer > sites ){
		File out = new File( new File( "." ).getAbsoluteFile(),
				"out/evidence/issue-38-" + label + ".sites.txt" );
		try {
			if (out.getParentFile() != null ) out.getParentFile().mkdirs();
			StringBuilder sb = new StringBuilder();
			sb.append( "# seed=" ).append( replay.seedText )
					.append( " hero=" ).append( replay.heroClass )
					.append( " steps=" ).append( replay.length() )
					.append( " baseDraws=" ).append( RandomTrace.baseDraws() ).append( '\n' );
			for (Map.Entry< String, Integer > e : sites.entrySet()){
				sb.append( e.getValue() ).append( '\t' ).append( e.getKey() ).append( '\n' );
			}
			java.nio.file.Files.write( out.toPath(),
					sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ));
		} catch (java.io.IOException e){
			System.err.println( "[WARN] cannot write " + out + ": " + e.getMessage() );
		}
	}


	private static final Map< String, String > BASELINES = new LinkedHashMap<>();

	private static void print( Map< String, Integer > sites, String label ){
		List< String > moved = new ArrayList<>();

		for (Map.Entry< String, Integer > entry : sites.entrySet()){
			String key = entry.getKey();
			int now = entry.getValue();
			String was = BASELINES.get( key );

			if (was == null){
				BASELINES.put( key, Integer.toString( now ));
				continue;
			}
			if (was.equals( Integer.toString( now ))) continue;

			moved.add( key + ": " + was + " -> " + now );
		}

		if (moved.isEmpty()){
			System.out.println( "  no draw site changed its tally against the first verification" );
			return;
		}

		System.out.println( "  " + moved.size() + " draw site(s) moved against the first verification:" );
		for (int i = 0; i < Math.min( 12, moved.size() ); i++) System.out.println( "    " + moved.get( i ));
	}
}