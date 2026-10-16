package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.watabou.utils.PathFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DialogCheck {

	private static final int CHECKS = 3;

	private static final String[] SEEDS = {
			"DIALOGCHECK-A", "DIALOGCHECK-B", "DIALOGCHECK-C", "DIALOGCHECK-D", "DIALOGCHECK-E"
	};

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-dialogcheck" ));
		HeadlessServices.disableSaving( true );

		checkTheGamesOwnDialogIsAgedAndAnswered();
		checkADialogClosesWhenTheGameClosesIt();
		checkADialogOpenedDuringATurnIsReportedInThatTurn();

		if (failures.isEmpty()){
			System.out.println( "[OK]     dialog answers: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  dialog answers: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkTheGamesOwnDialogIsAgedAndAnswered(){
		SPDEnv env = env();

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );

			int pit = -1, beside = -1;
			for (int c = 0; c < Dungeon.level.length(); c++){
				if (!Dungeon.level.pit[c] || Dungeon.level.solid[c]) continue;
				for (int d : PathFinder.NEIGHBOURS8){
					int n = c + d;
					if (n < 0 || n >= Dungeon.level.length()) continue;
					if (Dungeon.level.insideMap(n) && Dungeon.level.passable[n]
							&& !Dungeon.level.pit[n] && !Dungeon.level.solid[n]
							&& Actor.findChar(n) == null){
						pit = c; beside = n; break;
					}
				}
				if (pit >= 0) break;
			}
			if (pit < 0) continue;

			Dungeon.hero.pos = beside;
			Dungeon.hero.curAction = null;
			Dungeon.hero.ready = false;

			Action into = null;
			for (Action a : Action.values()){
				if (!a.directional()) continue;
				if (beside + a.dx + a.dy * Dungeon.level.width() == pit){ into = a; break; }
			}
			if (into == null ) continue;

			env.step( into, 0 );

			if (env.mode() != EnvMode.MENU || !WindowBridge.open()){
				fail( seed + ": walking the hero at a chasm left the environment in " + env.mode()
						+ " with " + WindowBridge.describe() + " rather than in MENU with the game's own"
						+ " question open. Hero.getCloser calls Chasm.heroJump, which raises a WndOptions"
						+ " through Game.runOnRenderThread - run inline headlessly - and GameScene.show"
						+ " parks it. If this is not MENU, the question never reached the agent." );
				return;
			}

			int steps = 0;
			while (WindowBridge.open() && steps < 60){
				env.step( Action.MENU_SELECT, 0 );
				steps++;
			}

			if (GameScene.headlessWindow() != null){
				fail( seed + ": the agent answered the game's \"jump?\" prompt " + steps + " times and it"
						+ " never closed. Chasm's dialog gates both its option and its hide on an elapsed"
						+ " counter its own update() increments, and headlessly nothing called that -"
						+ " WindowBridge.age() does it once per environment step, which is once per frame,"
						+ " the same approximation ReplayPlayer makes about a recorded step." );
				return;
			}

			int guard = 0;
			while (env.mode() != EnvMode.WORLD && env.running() && guard++ < 20){
				env.step( Action.CANCEL, 0 );
			}
			if (env.mode() != EnvMode.WORLD){
				fail( seed + ": the dialog closed but the environment stayed in " + env.mode() + "."
						+ " SPDEnv.step puts MENU back to WORLD after an option and lets settle() re-enter"
						+ " MENU only while the dialog is open, so a closed dialog has to return control." );
				return;
			}

			int depth = Dungeon.depth;
			int moves = 0;
			while (Dungeon.depth == depth && env.running() && moves++ < 10){
				env.step( env.mode() == EnvMode.WORLD ? into : Action.CANCEL, 0 );
			}

			if (Dungeon.depth != depth + 1){
				fail( seed + ": the agent answered the game's \"jump?\" prompt and then walked into the"
						+ " chasm " + moves + " time(s), and is on depth " + Dungeon.depth + " rather than "
						+ ( depth + 1 ) + ". The agent can be told yes by the game and still not cross the"
						+ " chasm it just asked about." );
				return;
			}

			System.out.println( "  the agent answers the game's own dialog: " + seed + ", " + steps
					+ " step(s) for the answer, then depth " + depth + " -> " + Dungeon.depth );
			return;
		}

		fail( "no seed produced a chasm the hero could be asked about, so this case measured nothing." );
	}

	private static void checkADialogClosesWhenTheGameClosesIt(){
		SPDEnv env = env();
		env.reset( SEEDS[0], HeroClass.WARRIOR );

		final boolean[] fired = { false };
		GameScene.show( new com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions(
				"DIALOGCHECK", "a dialog the game has not closed yet", "Yes", "No" ){

			private float elapsed = 0f;

			@Override
			public synchronized void update(){
				super.update();
				elapsed += com.watabou.noosa.Game.elapsed;
			}

			@Override
			public void hide(){
				if (elapsed > 0.2f) super.hide();
			}

			@Override
			protected void onSelect( int index ){
				if (index == 0 && elapsed > 0.2f) fired[0] = true;
			}
		} );

		if (!WindowBridge.open()){
			fail( "GameScene.show did not park an answerable window, so this case cannot test whether one"
					+ " survives an answer the game declines." );
			return;
		}

		WindowBridge.selectOption( 0 );

		if (!WindowBridge.open()){
			fail( "the dialog declined the option and was removed anyway. WindowBridge.selectOption used"
					+ " to clear the parked window on any press, which reports a dialog answered when the"
					+ " game threw the answer away - and Chasm's \"jump?\" prompt declines for a fifth of"
					+ " a second, so the agent's answer was discarded and its question taken away with it." );
			return;
		}
		if (fired[0]){
			fail( "the dialog fired its option before it had been up long enough, so the guard this case"
					+ " duplicates is not in the fixture and the first half of it proves nothing." );
			return;
		}

		for (int i = 0; i < 40 && WindowBridge.open(); i++) env.step( Action.WAIT, 0 );
		WindowBridge.selectOption( 0 );

		if (WindowBridge.open()){
			fail( "the dialog had been up long enough and was answered, and is still parked. A WndOptions"
					+ " button calls hide() before onSelect(), and the game's own hide() is what closes a"
					+ " parked window - which only works because it lives in a Group and the accessor reads"
					+ " that. Without the second half of this case the first half would also pass on a"
					+ " build where nothing ever closes a dialog." );
			return;
		}
		if (!fired[0]){
			fail( "the dialog closed without firing its option, so this fixture cannot tell an accepted"
					+ " answer from a discarded one." );
			return;
		}

		System.out.println( "  a dialog survives an answer the game declines, and closes on one it"
				+ " accepts" );
	}

	private static void checkADialogOpenedDuringATurnIsReportedInThatTurn(){
		SPDEnv env = env();
		env.reset( SEEDS[1], HeroClass.WARRIOR );

		env.step( Action.WAIT, 0 );
		if (!env.running()){
			fail( "the episode ended as " + env.endReason() + " after one turn, so this case measured"
					+ " nothing." );
			return;
		}

		GameScene.show( new com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions(
				"DIALOGCHECK", "raised while the world was moving", "Yes", "No" ) );

		env.step( Action.WAIT, 0 );

		if (env.mode() != EnvMode.MENU || !WindowBridge.open()){
			fail( "a dialog opened during the turn left the environment in " + env.mode() + " with "
					+ WindowBridge.describe() + " rather than in MENU with it. SPDEnv.settle only tested for"
					+ " a dialog at the top of its loop, so one raised from inside Hero.act() - which is"
					+ " where the game's \"jump?\" prompt comes from - was noticed a turn late, and the agent"
					+ " spent that turn with the world while a modal question was already up." );
			return;
		}

		WindowBridge.selectOption( 0 );
		if (WindowBridge.open()){
			fail( "the dialog was answered and is still up. A WndOptions button calls hide() before"
					+ " onSelect(), and the game's own hide() is what closes a parked window." );
			return;
		}

		System.out.println( "  a dialog opened during a turn is reported in that turn, and the answer"
				+ " closes it" );
	}

	private static SPDEnv env(){
		return new SPDEnv( new EnvConfig(), HeadlessGame.install() );
	}

	private static void fail( String message ){
		failures.add( message );
	}
}