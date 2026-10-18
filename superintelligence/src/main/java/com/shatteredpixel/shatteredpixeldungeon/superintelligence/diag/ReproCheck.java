package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngOrderLog;
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

		if (args.length > 0 && args[ 0 ].equals( "deathfixture" )){
			recordDeathFixture( config );
			return;
		}

		boolean baseline = args.length > 0 && args[ 0 ].equals( "baseline" );

		if (args.length > 0 && args[ 0 ].equals( "diff" )){
			diffDumps();
			return;
		}

		if (args.length > 0 && args[ 0 ].equals( "cross" )){
			cross( args );
			return;
		}

		if (args.length > 0 && args[ 0 ].equals( "order" )){
			orderLogs( args );
			return;
		}

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


private static void recordDeathFixture( EnvConfig config ) throws java.io.IOException {
		String seed = "DUELIST-LONG-16";

		ScriptedEpisode.Result episode = ScriptedEpisode.record( config, seed, HeroClass.DUELIST,
				0, seed.hashCode() );
		Replay replay = episode.replay;

		int accepted = 0;
		int refused = 0;
		for (Replay.Step step : replay.steps){
			if (step.accepted == 1) accepted++;
			else if (step.accepted == 0) refused++;
		}

		System.out.println( "recorded " + replay.length() + " steps, ends " + replay.termination
				+ ", depth " + replay.depth + ", score " + replay.score
				+ ", accepted " + accepted + ", refused " + refused );

		if (replay.termination == null || !replay.termination.equals( "DEATH" )){
			System.err.println( "[ERROR] the fixture ended as " + replay.termination
					+ " rather than DEATH, so it is not the death recording issues.md 26 is about" );
			System.exit( 1 );
			return;
		}
		if (refused == 0){
			System.err.println( "[ERROR] the fixture refused nothing, so it cannot say whether the two"
					+ " environments agree about a refusal" );
			System.exit( 1 );
			return;
		}

		ReplayIO.Verification check = ReplayIO.verify( replay,
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						config, HeadlessGame.install() ) );
		if (check.diverged){
			System.err.println( "[ERROR] the fixture does not reproduce: " + check.divergence );
			System.exit( 1 );
			return;
		}

		File out = new File( new File( "." ).getAbsoluteFile(),
				"out/evidence/duelist-acceptance-fixture.replay" );
		if (out.getParentFile() != null ) out.getParentFile().mkdirs();
		ReplayIO.write( replay, out );
		System.out.println( "verified headlessly in " + check.stepsVerified + " steps and wrote "
				+ out.getAbsolutePath() );
	}

private static final String SAVED =
			"out/evidence/issue-38-PARITY-ALPHA-WARRIOR.replay";


	private static void orderLogs( String[] args ) throws java.io.IOException {
		if (args.length < 2){
			System.err.println( "[ERROR] reprocheck order <recording> [<out-prefix>]" );
			System.exit( 1 );
			return;
		}

		java.io.File replayFile =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions
						.resolve( new java.io.File( args[ 1 ] ));
		if (!replayFile.exists()){
			System.err.println( "[ERROR] no recording at " + replayFile.getAbsolutePath() );
			System.exit( 1 );
			return;
		}

		String prefix = args.length > 2 ? args[ 2 ] : "out/evidence/issue-26/headless";
		prefix = com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions
				.resolve( new java.io.File( prefix ) ).getAbsolutePath();

		Replay replay = ReplayIO.read( replayFile );

		final boolean stepWindowTraced = replay.recordsRngFingerprint();

		RandomTrace.enable();
		RandomTrace.reset();
		RandomTrace.attribute();

		List< String > generation = new ArrayList<>();
		List< String > generationPushes = new ArrayList<>();

		ReplayIO.Verification result = ReplayIO.verify( replay,
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						new EnvConfig(), HeadlessGame.install() ),
				new ReplayIO.StepObserver() {
					@Override public void onReset(){
						generation.addAll( RandomTrace.siteLog() );
						generationPushes.addAll( RandomTrace.generatorLog() );
					}
					@Override public void onStep( int index, Replay.Step step, boolean running ){
						if (!stepWindowTraced) RandomTrace.enable();
					}
				} );

		int stepDraws = RandomTrace.siteLog().size();
		int stepPushes = RandomTrace.generatorLog().size();

		RandomTrace.attribute( false );
		RandomTrace.disable();

		RngOrderLog.write( prefix + ".gen", generation, generationPushes );
		if (stepWindowTraced) RngOrderLog.writeCurrent( prefix + ".all" );

		System.out.println( "headless " + replay.seedText + " " + replay.heroClass + ": diverged="
				+ result.diverged + (result.diverged ? " at step " + result.divergedAt + " - "
				+ result.divergence : "") + ", steps " + result.stepsVerified );
		System.out.println( "  generation window " + generation.size() + " ordered draws, "
				+ generationPushes.size() + " generator events" );
		System.out.println( "  step window " + stepDraws + " ordered draws, "
				+ stepPushes + " generator events" );
		System.out.println( "  wrote " + prefix + ".gen" + RngOrderLog.ORDER_SUFFIX + ", "
				+ prefix + ".all" + RngOrderLog.ORDER_SUFFIX );
	}


	private static void cross( String[] args ){
		if (args.length < 3){
			System.err.println( "[ERROR] reprocheck cross <left-prefix> <right-prefix>" );
			System.exit( 1 );
			return;
		}
		for (String window : new String[]{ ".gen", ".all", "" }){
			crossPair( args[ 1 ], args[ 2 ], window + RngOrderLog.ORDER_SUFFIX );
			crossPair( args[ 1 ], args[ 2 ], window + RngOrderLog.PUSHES_SUFFIX );
		}
	}

	private static void crossPair( String leftPrefix, String rightPrefix, String suffix ){
		List< String > left = readPath( leftPrefix + suffix );
		List< String > right = readPath( rightPrefix + suffix );
		if (left.isEmpty() || right.isEmpty()){
			System.out.println( "[" + suffix + "] nothing to compare: " + left.size() + " and "
					+ right.size() + " entries" );
			return;
		}
		System.out.println( "[" + suffix + "] " + OrderedDiff.report( leftPrefix, rightPrefix,
				left, right, 6 ));
	}

	private static List< String > readPath( String path ){
		List< String > lines = new ArrayList<>();
		java.nio.file.Path file = java.nio.file.Paths.get( path );
		if (!java.nio.file.Files.exists( file )) return lines;
		try {
			for (String line : java.nio.file.Files.readAllLines( file )){
				int tab = line.indexOf( '\t' );
				lines.add( tab < 0 ? line : line.substring( tab + 1 ));
			}
		} catch (java.io.IOException e){
			System.err.println( "[WARN] cannot read " + file + ": " + e.getMessage() );
		}
		return lines;
	}


	private static Replay readSaved(){
		try {
			return ReplayIO.read( new File( new File( "." ).getAbsoluteFile(), SAVED ) );
		} catch (java.io.IOException e){
			return null;
		}
	}


private static void verify( EnvConfig config, Replay replay, String label ){
		RandomTrace.enable();
		RandomTrace.reset();
		RandomTrace.attribute();

		ReplayIO.Verification result = ReplayIO.verify( replay,
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						config, HeadlessGame.install() ) );

		Map< String, Integer > sites = new LinkedHashMap<>( RandomTrace.sites() );
		List< String > order = new ArrayList<>( RandomTrace.siteLog() );
		List< String > pushes = new ArrayList<>( RandomTrace.generatorLog() );

		RandomTrace.attribute( false );
		RandomTrace.disable();

		System.out.println();
		System.out.println( "verify #" + label + ": diverged=" + result.diverged
				+ (result.diverged ? " at step " + result.divergedAt + " - " + result.divergence : "")
				+ ", steps " + result.stepsVerified + ", base draws " + RandomTrace.baseDraws()
				+ ", sites " + sites.size() + ", ordered draws " + order.size()
				+ ", generator events " + pushes.size() );
		System.out.println( "  secrets per region: " + secretsPerRegion() );
		System.out.println( "  guide pages found: " + guidePagesFound() );
		print( sites, label );
		dump( replay, label, sites );
		write( label + ".order.txt", order );
		write( label + ".pushes.txt", pushes );
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


	private static String guidePagesFound(){
		try {
			Class<?> document = Class.forName(
					"com.shatteredpixel.shatteredpixeldungeon.journal.Document" );
			Object guide = document.getField( "ADVENTURERS_GUIDE" ).get( null );
			java.lang.reflect.Field states = document.getDeclaredField( "pagesStates" );
			states.setAccessible( true );
			Object map = states.get( guide );
			return String.valueOf( map );
		} catch (Exception e){
			return "(unreadable: " + e.getClass().getSimpleName() + ")";
		}
	}

	private static final Map< String, String > BASELINES = new LinkedHashMap<>();

	private static String evidencePath( String name ){
		return "out/evidence/" + name;
	}

	private static void write( String name, List< String > lines ){
		File out = new File( new File( "." ).getAbsoluteFile(), evidencePath( name ));
		try {
			if (out.getParentFile() != null ) out.getParentFile().mkdirs();
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < lines.size(); i++) sb.append( i ).append( '\t' ).append( lines.get( i ) ).append( '\n' );
			java.nio.file.Files.write( out.toPath(), sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ));
		} catch (java.io.IOException e){
			System.err.println( "[WARN] cannot write " + out + ": " + e.getMessage() );
		}
	}

	private static List< String > read( String name ){
		File in = new File( new File( "." ).getAbsoluteFile(), evidencePath( name ));
		if ( !in.exists() ) return new ArrayList<>();
		List< String > lines = new ArrayList<>();
		try {
			for (String line : java.nio.file.Files.readAllLines( in.toPath() )){
				int tab = line.indexOf( '\t' );
				lines.add( tab < 0 ? line : line.substring( tab + 1 ));
			}
		} catch (java.io.IOException e){
			System.err.println( "[WARN] cannot read " + in + ": " + e.getMessage() );
		}
		return lines;
	}

	private static void diffDumps(){
		compare( "baseline", "baseline-again" );
		compare( "first", "second" );
		compare( "baseline", "first" );
	}

	private static void compare( String left, String right ){
		List< String > leftOrder = read( left + ".order.txt" );
		List< String > rightOrder = read( right + ".order.txt" );
		if (leftOrder.isEmpty() || rightOrder.isEmpty()){
			System.out.println( "[" + left + " vs " + right + "] no dump to compare; run reprocheck"
					+ " and reprocheck --args=\"baseline\" first" );
			return;
		}
		System.out.println( "[" + left + " vs " + right + "] ordered draws" );
		System.out.println( OrderedDiff.report( left, right, leftOrder, rightOrder, 3 ));

		List< String > leftPushes = read( left + ".pushes.txt" );
		List< String > rightPushes = read( right + ".pushes.txt" );
		System.out.println( "[" + left + " vs " + right + "] generator stack" );
		System.out.println( OrderedDiff.report( left, right, leftPushes, rightPushes, 2 ));
	}

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