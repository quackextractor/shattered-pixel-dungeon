package com.shatteredpixel.shatteredpixeldungeon.superintelligence;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CheckpointCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GaeCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GradientCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ModeCoverageCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ParallelCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RestartCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResetCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GraphCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.FreeActionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.BpttCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ActionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.EpisodeDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RolloutCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.PushCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.SlotCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ObserveCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.VerifyCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.StateCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TransitionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.FallCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.DialogCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.MobSpriteCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.PressCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.UpdateCostCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ValueScale;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.WeightsDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayProbe;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RunReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.DropFixture;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.PressFixture;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngTrace;

import java.io.File;
import java.io.IOException;
import java.util.function.ToIntFunction;


public class Main {

	public static void main( String[] args ){
		if (args.length == 0){
			usage();
			System.exit( 1 );
		}

		String command = args[ 0 ];
		String[] rest = new String[ args.length - 1 ];
		System.arraycopy( args, 1, rest, 0, rest.length );

		switch (command) {
		case "rollout":  rollout( rest );  break;
		case "verify":    verify( rest );    break;
		case "rngtrace": rngtrace( rest ); break;
		case "gradcheck": gradcheck( rest ); break;
		case "modecheck": ModeCoverageCheck.main( rest ); break;
		case "restartcheck": RestartCheck.main( rest ); break;
		case "resetcheck": ResetCheck.main( rest ); break;
		case "graphcheck": GraphCheck.main( rest ); break;
			case "actioncheck": ActionCheck.main( rest ); break;
			case "slotcheck": SlotCheck.main( rest ); break;
			case "verifycheck": VerifyCheck.main( rest ); break;
		case "updatecost": UpdateCostCheck.main( rest ); break;
		case "replayprobe": ReplayProbe.main( rest ); break;
		case "gaecheck": GaeCheck.main( rest ); break;
		case "collectcheck": CollectCheck.main( rest ); break;
			case "observecheck": ObserveCheck.main( rest ); break;
			case "episodediff": EpisodeDiff.main( rest ); break;
		case "checkpointcheck": CheckpointCheck.main( rest ); break;
		case "statecheck": StateCheck.main( rest ); break;
			case "freeactioncheck": FreeActionCheck.main( rest ); break;
			case "bpttcheck": BpttCheck.main( rest ); break;
		case "valuescale": ValueScale.main( rest ); break;
		case "parallelcheck": ParallelCheck.main( rest ); break;
		case "rewardcheck": RewardCheck.main( rest ); break;
		case "transitioncheck": TransitionCheck.main( rest ); break;
		case "fallcheck": FallCheck.main( rest ); break;
		case "dialogcheck": DialogCheck.main( rest ); break;
		case "presscheck": PressCheck.main( rest ); break;
		case "mobspritecheck": MobSpriteCheck.main( rest ); break;
		case "rolloutcheck": RolloutCheck.main( rest ); break;
			case "pushcheck": PushCheck.main( rest ); break;
			case "weightsdiff": WeightsDiff.main( rest ); break;
		case "replays":  ReplayCatalog.main( rest ); break;
		case "replaycheck": ReplayCatalogCheck.main( rest ); break;
		case "dropfixture": dropfixture( rest ); break;
		case "pressfixture": pressfixture( rest ); break;
		case "train":    Trainer( rest );   break;
			case "help":     usage();          break;
			default:
				System.err.println( "[ERROR] unknown command: " + command );
				usage();
				System.exit( 1 );
		}
	}

	private static void usage(){
		System.out.println( "Shattered Pixel Dungeon - headless training framework" );
		System.out.println();
System.out.println( "  rollout [options]        play one run headlessly and report it" );
		System.out.println( "  verify <file> [options]  re-run a saved run and confirm it reproduces" );
		System.out.println( "  modecheck                fail if an action mode cannot be reached" );
		System.out.println( "  restartcheck             fail if a restart does not rebuild the same first floor" );
		System.out.println( "  updatecost               measure what a PPO update costs, and project it" );
		System.out.println( "  gaecheck                 fail if the advantage implementations disagree" );
		System.out.println( "  collectcheck              fail if a collector recording will not replay" );
		System.out.println( "  checkpointcheck           fail if a policy does not survive disk, or a bad one loads" );
		System.out.println( "  weightsdiff <file>        report how far a checkpoint is from a fresh policy" );
		System.out.println( "  parallelcheck             fail if a parallel update differs from the serial one" );
		System.out.println( "  statecheck                fail if a sample does not replay to its rollout's value" );
		System.out.println( "  rewardcheck               fail if ending an episode can be cheaper than dying" );
		System.out.println( "  freeactioncheck           fail if a free action can be repeated forever, or a talent point cannot be spent" );
		System.out.println( "  bpttcheck                  fail if the recurrent gradient does not cross a timestep" );
		System.out.println( "  transitioncheck           fail if descending ends the episode, or is worth nothing" );
		System.out.println( "  fallcheck                fail if a chasm fall is dropped, or inherits an earlier episode" );
		System.out.println( "  dialogcheck              fail if an answer cannot reach a dialog the game opened" );
		System.out.println( "  presscheck              fail if a dropped item does not press the cell it landed on" );
		System.out.println( "  replayprobe               report how far a shuffled replay drifts" );
		System.out.println( "  valuescale                report the critic's targets against what it can reach" );
		System.out.println( "  replays [--dir d]...     list recordings, grouped by hero class and ranked" );
		System.out.println( "  replaycheck               fail if the replay catalog misgroups or misranks" );
		System.out.println( "  dropfixture --save <f>    record the DROP-in-SLOT fixture the viewer gates play" );
		System.out.println( "  pressfixture --save <f>   record the throw-that-presses fixture the viewer gates play" );
		System.out.println( "  train [options]          run the PPO trainer across worker JVMs" );
		System.out.println();
		System.out.println( "options:" );
		System.out.println( "  --seed <text>       seed to lock the run to (default: random)" );
		System.out.println( "  --hero <class>      WARRIOR, MAGE, HUNTRESS, ROGUE, DUELIST" );
		System.out.println( "  --save <file>       write the run out as a replay" );
		System.out.println( "  --out <dir>         working directory for saves and replays" );
		System.out.println( "  --max-turns <n>     override the per-floor turn cap" );
		System.out.println( "  --config <file>     load environment and learning settings from a properties file" );
		System.out.println( "  --no-color          disable ANSI colour" );
		System.out.println();
		System.out.println( "settings may also come from SPD_* environment variables (rl.learning_rate ->" );
		System.out.println( "SPD_RL_LEARNING_RATE), which override the file. Flags override both." );
		System.out.println( "See superintelligence/superintelligence.properties for every key." );
	}

private static void rollout( String[] args ){
		Options options = Options.parse( args );

		if ( options.seedProvided && (options.seed == null || options.seed.trim().isEmpty()) ){
			System.err.println( "[ERROR] --seed was given but empty. Omit the flag entirely for a "
					+ "random seed, which is then recorded in the replay." );
			System.exit( 1 );
			return;
		}

		ResourceStats.Interval timing = ResourceStats.start();

		boot( options );

		EnvConfig config = configFor( options );
		if (options.maxTurns > 0) config.turnLimitPerFloor = options.maxTurns;

		HeadlessGame game = HeadlessGame.install();
		SPDEnv env = new SPDEnv( config, game );

		ResourceStats.Interval simulation = ResourceStats.start();
		long start = System.nanoTime();

		ScriptedEpisode.Result episode =
				ScriptedEpisode.record( config, options.seed, options.hero, 0, options.seed.hashCode() );

		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		simulation.stop();
		timing.stop();

		ReplayRecorder recorder = new ReplayRecorder();

		System.out.println();
		System.out.println( episode.report.render( 72 ) );
		System.out.println( episode.report.renderScoreOverFloors( 72 ) );

		System.out.println();
System.out.println( Ansi.wrap( "outcome", Ansi.DIM ) + "  " + episode.env.endReason()
				+ (episode.env.truncated() ? " (truncated)" : "") );
		System.out.println( Ansi.wrap( "simulation", Ansi.DIM ) + " "
				+ String.format( "%d turns in %.2f s (%,.0f turns/s) on %.2f cores",
						episode.env.turnsTotal(), simulation.wallSeconds(),
						ResourceStats.stepsPerSecond( episode.env.turnsTotal(), simulation.wallSeconds() ),
						Math.max( 0, simulation.coresUsed() ) ) );

		System.out.println();
		System.out.println( ResourceStats.renderSummary( "resources (whole command)",
				timing, episode.env.turnsTotal() ) );

		if (options.saveTo != null){
			try {
				ReplayIO.write( episode.replay, options.saveTo );
				System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   wrote "
						+ episode.replay.length() + " steps to " + options.saveTo.getPath() );
			} catch (IOException e){
				System.err.println( "[ERROR] cannot write the replay to " + options.saveTo
						+ " (" + e.getClass().getSimpleName() + ")" );
				System.exit( 1 );
			}
		}
	}


	private static void dropfixture( String[] args ){
		writeFixture( "dropfixture", args, DropFixture::recordAndWrite,
				replay -> firstStep( replay, "DROP", "SLOT" ),
				"DROP in SLOT" );
	}


	private static void pressfixture( String[] args ){
		writeFixture( "pressfixture", args, PressFixture::recordAndWrite,
				replay -> firstStep( replay, "USE", "TARGETING" ),
				"throw resolved in TARGETING" );
	}


	private static void writeFixture( String name, String[] args,
			FixtureWriter writer, ToIntFunction< Replay > witness, String covers ){
		Options options = Options.parse( args );
		boot( options );

		if (options.saveTo == null){
			System.err.println( "[ERROR] " + name + " needs --save <file>; it writes the recording rather"
					+ " than reporting one" );
			System.exit( 1 );
			return;
		}

		EnvConfig config = configFor( options );

		Replay replay;
		try {
			replay = writer.write( config, options.saveTo );
		} catch (IllegalStateException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return;
		} catch (IOException e){
			System.err.println( "[ERROR] cannot write the fixture to " + options.saveTo.getPath()
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		int covered = witness.applyAsInt( replay );
		if (covered < 0){
			System.err.println( "[ERROR] the fixture recorded no " + covers + ", so it is a recording that"
					+ " would pass every viewer gate while covering nothing. Refusing to write it." );
			System.exit( 1 );
			return;
		}

		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), HeadlessGame.install() );
		ReplayIO.Verification result = ReplayIO.verify( replay, env );

		if (!result.passed()){
			System.err.println( "[ERROR] the fixture does not reproduce, so it is not a fixture:"
					+ ( result.diverged ? " diverged at step " + result.divergedAt + " - "
						+ result.divergence : " refused at step " + result.unwatchableAt + " - "
						+ result.unwatchable ) );
			System.exit( 1 );
			return;
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     wrote " + replay.length()
				+ " steps, first " + covers + " at step " + covered + ", to " + options.saveTo.getPath()
				+ "; reproduced in " + result.stepsVerified + " steps" );
	}


	private static int firstStep( Replay replay, String action, String mode ){
		for (int i = 0; i < replay.steps.size(); i++){
			Replay.Step step = replay.steps.get( i );
			if (step.action.equals( action ) && step.mode.equals( mode )) return i;
		}
		return -1;
	}

	private interface FixtureWriter {
		Replay write( EnvConfig config, File file ) throws IOException;
	}


	static EnvConfig configFor( Options options ){
		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder.Loaded loaded =
					com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder
							.load( options.configFile );
			System.out.println( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env
					.EnvConfigBinder.describe( options.configFile, loaded.keysApplied ));
			return loaded.config;
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read the configuration at " + options.configFile
					+ " (" + e.getClass().getSimpleName() + "): " + e.getMessage() );
			System.exit( 1 );
			return new EnvConfig();
		} catch (IllegalArgumentException e){

			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return new EnvConfig();
		}
	}


	private static void rngtrace( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] rngtrace needs a replay file" );
			System.exit( 1 );
			return;
		}

		File file = new File( args[ 0 ] );
		Options options = Options.parse( tail( args ) );
		boot( options );

		java.nio.file.Path out = options.rngTraceOut;
		java.nio.file.Path golden = options.rngTraceGolden;

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (IOException e){

			System.err.println( "[ERROR] cannot read the replay " + file
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		if ( replay.seedText == null || replay.seedText.trim().isEmpty() ){
			System.err.println( "[ERROR] " + file.getName() + " records no seed, so its world cannot be "
					+ "rebuilt and there is nothing to trace." );
			System.exit( 1 );
			return;
		}

		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), HeadlessGame.install() );

		RngTrace trace = new RngTrace();
		com.watabou.utils.RandomTrace.enable();

		com.watabou.utils.RandomTrace.attribute();

		ReplayIO.verify( replay, env, trace.observer() );

		if (options.rngTraceSites != null){
			try {
				java.nio.file.Files.write( options.rngTraceSites,
						String.join( "\n", com.watabou.utils.RandomTrace.siteReport() )
								.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
			} catch (IOException e){
				System.err.println( "[ERROR] cannot write the draw-site tally to "
						+ options.rngTraceSites + " (" + e.getClass().getSimpleName() + ")" );
				System.exit( 1 );
				return;
			}
			System.out.println( "wrote " + com.watabou.utils.RandomTrace.sites().size()
					+ " draw sites to " + options.rngTraceSites );
		}
		com.watabou.utils.RandomTrace.attribute( false );

		com.watabou.utils.RandomTrace.disable();

		if (out != null){
			try {
				trace.writeTo( out );
} catch (IOException e){
			System.err.println( "[ERROR] cannot write the rng trace to " + out
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}
			System.out.println( "wrote " + trace.size() + " trace samples to " + out );
		}

		if (golden != null){
			String problem = RngTrace.compare( golden, trace );
			if (problem.isEmpty()){
				System.out.println( "[OK]     rng trace matches " + golden );
			} else {
				System.out.println( "[ERROR] rng trace differs from " + golden );
				System.out.println( "        " + problem.replace( "\n", "\n        " ) );
				System.exit( 1 );
			}
		}

		if (out == null && golden == null){
			System.out.println( trace.render() );
		}
	}

	private static void verify( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] replay needs a file" );
			System.exit( 1 );
			return;
		}

		File file = new File( args[ 0 ] );
		Options options = Options.parse( tail( args ) );
		boot( options );

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (IOException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		if ( replay.seedText == null || replay.seedText.trim().isEmpty() ){
			System.err.println( "[ERROR] " + file.getName() + " records no seed (header field 'seed=' is empty)."
					+ " Its world cannot be rebuilt, so it cannot be verified. Re-record with a seed." );
			System.exit( 1 );
			return;
		}

		System.out.println( "verify: seed=" + replay.seedText
				+ " hero=" + replay.heroClass
				+ " steps=" + replay.steps.size()
				+ " recorded score=" + replay.score );

		HeadlessGame game = HeadlessGame.install();
		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), game );

		long start = System.nanoTime();
		ReplayIO.Verification result = ReplayIO.verify( replay, env );
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		RunReport report = new RunReport( env.ledger(), replay.seedText );

		System.out.println();
		System.out.println( report.render( 72 ) );
		System.out.println();

		if (result.diverged){

			System.err.println( "[ERROR] replay diverged at step " + result.divergedAt
					+ " of " + replay.steps.size() );
			System.err.println( "        " + result.divergence );
			System.err.println( "        action " + replay.steps.get( result.divergedAt ).action
					+ " slot " + replay.steps.get( result.divergedAt ).slot
					+ " mode " + replay.steps.get( result.divergedAt ).mode );
			System.exit( 2 );
		} else if (result.unwatchableAt >= 0){

			System.err.println( "[ERROR] replay reproduced in " + result.stepsVerified
					+ " steps but is not watchable:" );
			System.err.println( "        " + result.unwatchable );
			System.err.println( "        A recording whose slot index cannot be resolved against its own"
					+ " bindings verifies on whatever else it carries. Reproducing is not the same claim"
					+ " as being playable, and this file only ever made the first one." );
			System.exit( 3 );
		} else {
			System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     replay reproduced in "
					+ result.stepsVerified + " steps, " + elapsedMs + " ms" );
			System.out.println( Ansi.wrap( "score", Ansi.DIM ) + "    recorded "
					+ replay.score + ", reproduced " + result.score
					+ (Math.abs( replay.score - result.score ) < 1e-6 ? "" : "  [WARN] differs") );
		}
	}


	private static void gradcheck( String[] args ){
		boolean verbose = false;
		for (String a : args){
			if (a.equals( "--verbose" )) verbose = true;
		}

		GradientCheck check = new GradientCheck( new EnvConfig(), 7 );
		int failures = check.run( verbose );

		if (failures > 0) System.exit( 1 );
	}


	private static boolean reset( SPDEnv env, Options options ){
		try {
			env.reset( options.seed, options.hero );
			return true;
		} catch (IllegalArgumentException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return false;
		}
	}

	private static String[] tail( String[] args ){
		String[] out = new String[ Math.max( 0, args.length - 1 ) ];
		System.arraycopy( args, 1, out, 0, out.length );
		return out;
	}


	static void boot( Options options ){
		if (options.noColor) Ansi.enable( false );

		File workDir = options.outDir != null
				? options.outDir
				: com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault();

		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( !options.persistSaves );
	}


	static class Options {
		String seed = "";
      boolean seedProvided = false;

		java.nio.file.Path configFile;
		HeroClass hero = HeroClass.WARRIOR;
		File saveTo;
		File outDir;
		int maxTurns = 0;
boolean noColor = false;
      boolean persistSaves = false;

      java.nio.file.Path rngTraceOut;

      java.nio.file.Path rngTraceGolden;

      java.nio.file.Path rngTraceSites;

		static Options parse( String[] args ){
			Options o = new Options();
			for (int i = 0; i < args.length; i++){
				switch (args[ i ]) {
case "--seed":
					o.seed = args[ ++i ];
					o.seedProvided = true;
					break;
					case "--hero":
						o.hero = HeroClass.valueOf( args[ ++i ].toUpperCase() );
						break;
					case "--save":
						o.saveTo = new File( args[ ++i ] );
						break;
					case "--out":
						o.outDir = new File( args[ ++i ] );
						break;
case "--max-turns":
					o.maxTurns = Integer.parseInt( args[ ++i ] );
					break;
				case "--config":
					o.configFile = java.nio.file.Paths.get( args[ ++i ] );
					break;
case "--no-color":
					o.noColor = true;
					break;
				case "--rng-trace-out":
					o.rngTraceOut = java.nio.file.Paths.get( args[ ++i ] );
					break;
				case "--rng-trace-sites":
					o.rngTraceSites = java.nio.file.Paths.get( args[ ++i ] );
					break;
				case "--rng-trace-golden":
					o.rngTraceGolden = java.nio.file.Paths.get( args[ ++i ] );
					break;
					case "--persist-saves":
						o.persistSaves = true;
						break;
					default:
						if (args[ i ].startsWith( "--" )){
							System.err.println( "[WARN] unknown option: " + args[ i ] );
						}
				}
			}
			return o;
		}
	}


	static void Trainer( String[] args ){
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainerMain.main( args );
	}
}
