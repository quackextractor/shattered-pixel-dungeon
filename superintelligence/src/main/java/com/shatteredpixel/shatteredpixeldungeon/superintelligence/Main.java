package com.shatteredpixel.shatteredpixeldungeon.superintelligence;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.Cli;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.FixtureCommand;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.RngTraceCommand;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.RunCommand;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.VerifyCommand;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ActionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.BpttCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CheckpointCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CurriculumCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.DialogCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.DocCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.EpisodeDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.FallCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.FreeActionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GaeCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GraphCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ModeCoverageCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.MobSpriteCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ObserveCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RenderDrawCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ParallelCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.PressCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.PushCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayProbe;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResetCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RestartCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RolloutCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.SamplerCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.SeedScheduleCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.SlotCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.StateCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TransitionCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TravelCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TeacherCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GoExplore;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.UpdateCostCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ValueScale;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.VerifyCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.WeightsDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayListing;

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
		case "rollout":  RunCommand.main( rest );  break;
		case "verify":    VerifyCommand.main( rest );    break;
		case "rngtrace": RngTraceCommand.main( rest ); break;
		case "gradcheck": System.exit( Cli.gradcheck( rest ) ); break;
		case "modecheck": ModeCoverageCheck.main( rest ); break;
		case "restartcheck": RestartCheck.main( rest ); break;
		case "resetcheck": ResetCheck.main( rest ); break;
		case "graphcheck": GraphCheck.main( rest ); break;
		case "actioncheck": ActionCheck.main( rest ); break;
		case "slotcheck": SlotCheck.main( rest ); break;
		case "verifycheck": VerifyCheck.main( rest ); break;
		case "updatecost": UpdateCostCheck.main( rest ); break;
		case "gaecheck": GaeCheck.main( rest ); break;
		case "collectcheck": CollectCheck.main( rest ); break;
		case "observecheck": ObserveCheck.main( rest ); break;
		case "renderdrawcheck": RenderDrawCheck.main( rest ); break;
		case "replayprobe": ReplayProbe.main( rest ); break;
		case "episodediff": EpisodeDiff.main( rest ); break;
		case "checkpointcheck": CheckpointCheck.main( rest ); break;
		case "statecheck": StateCheck.main( rest ); break;
		case "freeactioncheck": FreeActionCheck.main( rest ); break;
		case "bpttcheck": BpttCheck.main( rest ); break;
		case "valuescale": ValueScale.main( rest ); break;
		case "parallelcheck": ParallelCheck.main( rest ); break;
		case "rewardcheck": RewardCheck.main( rest ); break;
		case "transitioncheck": TransitionCheck.main( rest ); break;
		case "travelcheck": TravelCheck.main( rest ); break;
		case "teachercheck": TeacherCheck.main( rest ); break;
		case "goexplore": GoExplore.main( rest ); break;
		case "fallcheck": FallCheck.main( rest ); break;
		case "dialogcheck": DialogCheck.main( rest ); break;
		case "presscheck": PressCheck.main( rest ); break;
		case "mobspritecheck": MobSpriteCheck.main( rest ); break;
		case "rolloutcheck": RolloutCheck.main( rest ); break;
		case "pushcheck": PushCheck.main( rest ); break;
		case "curriculumcheck": CurriculumCheck.main( rest ); break;
		case "samplercheck": SamplerCheck.main( rest ); break;
		case "seedcheck": SeedScheduleCheck.main( rest ); break;
		case "doccheck": DocCheck.main( rest ); break;
		case "weightsdiff": WeightsDiff.main( rest ); break;
		case "replays":  ReplayListing.main( rest ); break;
		case "replaycheck": com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag
				.ReplayCatalogCheck.main( rest ); break;
		case "dropfixture": FixtureCommand.dropfixture( rest ); break;
		case "pressfixture": FixtureCommand.pressfixture( rest ); break;
		case "train":    com.shatteredpixel.shatteredpixeldungeon.superintelligence.train
				.TrainerMain.main( rest ); break;
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
		System.out.println( "  gaecheck                 fail if GAE disagrees with the discounted sum, or an update moves the wrong way" );
		System.out.println( "  collectcheck             fail if a collector recording will not replay" );
		System.out.println( "  checkpointcheck           fail if a policy does not survive disk, or a bad one loads" );
		System.out.println( "  weightsdiff <file>        report how far a checkpoint is from a fresh policy" );
		System.out.println( "  parallelcheck             fail if a parallel update differs from the serial one" );
		System.out.println( "  statecheck                fail if a sample does not replay to its rollout's value" );
		System.out.println( "  rewardcheck               fail if ending an episode can be cheaper than dying" );
		System.out.println( "  freeactioncheck           fail if a free action can be repeated forever, or a talent point cannot be spent" );
		System.out.println( "  bpttcheck                  fail if the recurrent gradient does not cross a timestep" );
		System.out.println( "  pushcheck                fail if the policy push is not chunked, or retries a failed write" );
		System.out.println( "  curriculumcheck          fail if the shaping fade is wired to nothing" );
		System.out.println( "  samplercheck             fail if sampling shatters the recurrent chunk" );
		System.out.println( "  seedcheck                 fail if a resumed run replays generation 0's seeds" );
		System.out.println( "  doccheck                 fail if the documentation is unreadable or stale" );
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
}