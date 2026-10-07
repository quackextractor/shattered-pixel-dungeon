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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.StateCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.UpdateCostCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ValueScale;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.WeightsDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayProbe;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RunReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

/**
 * Entry point for the headless training framework.
 *
 * Three subcommands, in the order they are useful:
 *
 * <pre>
 *   rollout  play one run headlessly with the scripted policy, print the run report, optionally
 *            save it as a replay. This is the smoke test: if this cannot complete a floor the
 *            environment is not wired up.
 *   verify    re-execute a saved run headlessly and confirm it still reproduces.
 *   train    run the PPO trainer across worker JVMs. See train.Trainer.
 * </pre>
 */
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
		case "gradcheck": gradcheck( rest ); break;
		case "modecheck": ModeCoverageCheck.main( rest ); break;
		case "restartcheck": RestartCheck.main( rest ); break;
		case "resetcheck": ResetCheck.main( rest ); break;
		case "graphcheck": GraphCheck.main( rest ); break;
		case "updatecost": UpdateCostCheck.main( rest ); break;
		case "replayprobe": ReplayProbe.main( rest ); break;
		case "gaecheck": GaeCheck.main( rest ); break;
		case "collectcheck": CollectCheck.main( rest ); break;
		case "checkpointcheck": CheckpointCheck.main( rest ); break;
		case "statecheck": StateCheck.main( rest ); break;
		case "valuescale": ValueScale.main( rest ); break;
		case "parallelcheck": ParallelCheck.main( rest ); break;
		case "rewardcheck": RewardCheck.main( rest ); break;
		case "weightsdiff": WeightsDiff.main( rest ); break;
		case "replays":  ReplayCatalog.main( rest ); break;
		case "replaycheck": ReplayCatalogCheck.main( rest ); break;
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
		System.out.println( "  replayprobe               report how far a shuffled replay drifts" );
		System.out.println( "  valuescale                report the critic's targets against what it can reach" );
		System.out.println( "  replays [--dir d]...     list recordings, grouped by hero class and ranked" );
		System.out.println( "  replaycheck               fail if the replay catalog misgroups or misranks" );
		System.out.println( "  train [options]          run the PPO trainer across worker JVMs" );
		System.out.println();
		System.out.println( "options:" );
		System.out.println( "  --seed <text>       seed to lock the run to (default: random)" );
		System.out.println( "  --hero <class>      WARRIOR, MAGE, HUNTRESS, ROGUE, DUELIST" );
		System.out.println( "  --save <file>       write the run out as a replay" );
		System.out.println( "  --out <dir>         working directory for saves and replays" );
		System.out.println( "  --max-turns <n>     override the per-floor turn cap" );
		System.out.println( "  --no-color          disable ANSI colour" );
	}

	// --------------------------------------------------------------------------- rollout

private static void rollout( String[] args ){
		Options options = Options.parse( args );

		//started before anything else so the resource block is the cost of the whole command,
		//level generation and engine boot included, not just the step loop
		ResourceStats.Interval timing = ResourceStats.start();

		boot( options );

		EnvConfig config = new EnvConfig();
		if (options.maxTurns > 0) config.turnLimitPerFloor = options.maxTurns;

		HeadlessGame game = HeadlessGame.install();
		SPDEnv env = new SPDEnv( config, game );

		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), options.seed.hashCode() );
		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( options.seed, options.hero.name(), 0, config.turnLimitPerFloor );

		if (!reset( env, options )) return;


RunReport report = new RunReport( env.ledger(), options.seed );
		int[] slot = new int[ 1 ];

		ResourceStats.Interval simulation = ResourceStats.start();
		long start = System.nanoTime();
		double cumulative = 0;

		while (env.running()){
			Action action = policy.choose( env, slot );
			recorder.record( action, slot[ 0 ], env.mode() );

			float reward = (float) env.step( action, slot[ 0 ] );
			recorder.afterStep( env.heroPosition(), reward );
			cumulative += reward;

			report.sample( (float) env.ledger().total(), env.depth() );
		}
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		simulation.stop();
		timing.stop();

		recorder.end( env.ledger().total(), env.depth(), env.turnsTotal(), 0 );

		System.out.println();
		System.out.println( report.render( 72 ) );
		System.out.println( report.renderScoreOverFloors( 72 ) );

		System.out.println();
System.out.println( Ansi.wrap( "outcome", Ansi.DIM ) + "  " + env.endReason()
				+ (env.truncated() ? " (truncated)" : "") );
		System.out.println( Ansi.wrap( "simulation", Ansi.DIM ) + " "
				+ String.format( "%d turns in %.2f s (%,.0f turns/s) on %.2f cores",
						env.turnsTotal(), simulation.wallSeconds(),
						ResourceStats.stepsPerSecond( env.turnsTotal(), simulation.wallSeconds() ),
						Math.max( 0, simulation.coresUsed() ) ) );

		System.out.println();
		System.out.println( ResourceStats.renderSummary( "resources (whole command)",
				timing, env.turnsTotal() ) );

		if (options.saveTo != null){
			try {
				ReplayIO.write( recorder.replay(), options.saveTo );
				System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   wrote "
						+ recorder.replay().length() + " steps to " + options.saveTo.getPath() );
			} catch (java.io.IOException e){
				System.err.println( "[ERROR] could not write replay: " + e.getMessage() );
				System.exit( 1 );
			}
		}
	}

	// --------------------------------------------------------------------------- replay

	private static void verify( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] replay needs a file" );
			System.exit( 1 );
			return;
		}

		java.io.File file = new java.io.File( args[ 0 ] );
		Options options = Options.parse( tail( args ) );
		boot( options );

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (java.io.IOException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		System.out.println( "verify: seed=" + (replay.seedText.isEmpty() ? "<random>" : replay.seedText)
				+ " hero=" + replay.heroClass
				+ " steps=" + replay.steps.size()
				+ " recorded score=" + replay.score );

		HeadlessGame game = HeadlessGame.install();
		SPDEnv env = new SPDEnv( new EnvConfig(), game );

		long start = System.nanoTime();
		ReplayIO.Verification result = ReplayIO.verify( replay, env );
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		RunReport report = new RunReport( env.ledger(), replay.seedText );

		System.out.println();
		System.out.println( report.render( 72 ) );
		System.out.println();

		if (result.diverged){
			//a replay that stops reproducing means the seed lock has been broken somewhere, and
			//every locked-seed comparison the trainer makes becomes meaningless
			System.err.println( "[ERROR] replay diverged at step " + result.divergedAt
					+ " of " + replay.steps.size() );
			System.err.println( "        recorded position no longer matches the live game" );
			System.exit( 2 );
		} else {
			System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "     replay reproduced in "
					+ result.stepsVerified + " steps, " + elapsedMs + " ms" );
			System.out.println( Ansi.wrap( "score", Ansi.DIM ) + "    recorded "
					+ replay.score + ", reproduced " + result.score
					+ (Math.abs( replay.score - result.score ) < 1e-6 ? "" : "  [WARN] differs") );
		}
	}

// --------------------------------------------------------------------------- gradients

	/**
	 * Checks the network's analytic gradients against finite differences.
	 *
	 * Exits non-zero on a mismatch, so it is usable as a gate rather than only a report. Needs no
	 * game state, which is why it does not boot the headless services.
	 */
	private static void gradcheck( String[] args ){
		boolean verbose = false;
		for (String a : args){
			if (a.equals( "--verbose" )) verbose = true;
		}

		GradientCheck check = new GradientCheck( new EnvConfig(), 7 );
		int failures = check.run( verbose );

		if (failures > 0) System.exit( 1 );
	}

	// --------------------------------------------------------------------------- shared

	/**
	 * Resets the env, reporting a bad seed as a message rather than a stack trace.
	 *
	 * A seed the game silently refuses is a user error, not a crash: the run would otherwise
	 * continue on a random seed and look like it worked.
	 *
	 * @return false if the run was rejected and the caller should stop
	 */
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

	/**
	 * Installs the headless services.
	 *
	 * Order matters: services first, because {@code HeadlessGame} calls into Gdx statics during
	 * construction, then saving is disabled, because the only writes a rollout performs are
	 * incidental and there are millions of turns' worth of them.
	 */
	static void boot( Options options ){
		if (options.noColor) Ansi.enable( false );

		java.io.File workDir = options.outDir != null
				? options.outDir
				: new java.io.File( System.getProperty( "java.io.tmpdir" ), "spd-headless" );

		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( !options.persistSaves );
	}

	/** Parsed command line options. */
	static class Options {
		String seed = "";
		HeroClass hero = HeroClass.WARRIOR;
		java.io.File saveTo;
		java.io.File outDir;
		int maxTurns = 0;
		boolean noColor = false;
		boolean persistSaves = false;

		static Options parse( String[] args ){
			Options o = new Options();
			for (int i = 0; i < args.length; i++){
				switch (args[ i ]) {
					case "--seed":
						o.seed = args[ ++i ];
						break;
					case "--hero":
						o.hero = HeroClass.valueOf( args[ ++i ].toUpperCase() );
						break;
					case "--save":
						o.saveTo = new java.io.File( args[ ++i ] );
						break;
					case "--out":
						o.outDir = new java.io.File( args[ ++i ] );
						break;
					case "--max-turns":
						o.maxTurns = Integer.parseInt( args[ ++i ] );
						break;
					case "--no-color":
						o.noColor = true;
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

	/** Placeholder until the trainer lands; replaced by train.Trainer.main. */
	static void Trainer( String[] args ){
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.main( args );
	}
}
