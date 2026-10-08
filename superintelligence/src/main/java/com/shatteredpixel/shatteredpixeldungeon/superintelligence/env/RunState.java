package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Bones;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.badlogic.gdx.Gdx;

/**
 * The process-scoped static state a new run must not inherit.
 *
 * <p>The game's simulation state is almost entirely static - {@code Dungeon.hero}, {@code Dungeon.level},
 * {@code Actor.now}, the whole actor registry - which is why one JVM hosts one run. Three statics are
 * worse than that: they belong to no run at all. {@code GameScene.pendingCellListener} and
 * {@code GameScene}'s headless dialog slot are where the engine parks a request the game expects the
 * *player* to answer, and {@code SlotAction.pendingUseItem} is where this framework parks the item an
 * agent chose to aim. All three are written during an episode, survive it, and are read by whatever
 * runs next in the same process.
 *
 * <p><b>What that cost.</b> An episode that ended while an item was mid-aim left the listener armed.
 * {@code SPDEnv.reset} did not clear it, so the new episode's first {@code settle} - which tests for a
 * pending cell before it runs a single turn - reported {@code TARGETING} and read every one of the
 * agent's actions as a target choice. The hero never moved, and every recording made in that state
 * diverged at step 0. Measured: 4 of 10 recorded runs diverged when verified in sequence, and the same
 * files verified cleanly one at a time. See {@link #clearRunStatics()} and {@code diag.ResetCheck}.
 *
 * <p><b>Why this is one method rather than a list to follow.</b> {@code SPDEnv.reset} and the desktop
 * replay viewer are two implementations of "begin a run in this process", and the fault was precisely
 * that one of them cleared a subset of the statics the other cleared. One method cannot be half
 * implemented by a caller who forgets a line: a caller either calls it or does not, and the set is
 * reviewable in one place. The viewer still needs its own conditional per-step handling - it must keep
 * the aim request alive across the step that consumes it - so it calls this at run boundaries only and
 * keeps the per-step preamble where it belongs.
 *
 * <p>Remains are the fourth, and the one that is not obviously a "pending request". A hero who dies
 * leaves their belongings and one class-specific remnant behind, and the next run picks them up where
 * they fell - which is the feature working as designed for a player returning to a dungeon, and
 * contamination for an environment playing thousands of independent runs in one process. It arrives two
 * ways: {@link com.shatteredpixel.shatteredpixeldungeon.Bones}' cached statics, and a {@code bones.dat}
 * the death wrote to the process's file root. Both are cleared here, and both are needed: clearing only
 * the statics leaves the file for the next run to read, and clearing only the file leaves the statics
 * this process already loaded.
 *
 * <p><b>What it cost, measured.</b> A parity sweep over 8 seeds and 4 hero classes recorded 32 runs and
 * replayed each one, and 2 of the 32 diverged - both on inventory, both on a remnant item. The recording
 * carried one hero class's remains and the replay carried its own, because the recording run died first
 * and left them behind. The divergence was not one step late: the heap appeared on a floor the hero had
 * not visited yet, so every step from floor generation onwards described a different dungeon. No earlier
 * gate could see it, because every one of them either plays a single episode per process or never lets
 * the hero die.
 *
 * <p><b>Deliberately not here: {@code Actor.clear()} and the RNG reseed.</b> Both are reached through
 * {@code Dungeon.init()}, which {@link LevelPipeline#startRun} calls: {@code Actor.clear()} empties the
 * actor registry and {@code Random.reseedBase(seed)} reseeds the gameplay generator, reseeding
 * {@code PRandom} with it. Calling either from here would reseed from the wrong value - the caller has
 * not resolved the run seed yet at the point a caller would naturally reach for this - and would clear
 * actors that {@code Dungeon.init()} is about to clear anyway. If a static is not reachable from the
 * calls below, it belongs in {@code Dungeon.init}, not here, and finding that out is a question for the
 * game's own source rather than something to work around from here.
 */
public class RunState {

	private RunState() {}

	/**
	 * Drops every pending-request static, so the next run starts from a process that owes it nothing.
	 *
	 * <p>Order is not significant - these are independent fields on independent owners - and the
	 * per-line comments below name what each one is, because a reader who has to grep for
	 * {@code pendingCellListener} to learn why a reset method exists is reading the wrong thing.
	 */
	public static void clearRunStatics(){
		//An armed aim request, stashed by GameScene.selectCell. Read by SPDEnv.settle before the
		//scheduler runs, which is what makes it decide the new episode's first mode.
		GameScene.clearPendingCellListener();

		//A dialog the game opened through GameScene.show, parked headlessly. Read by WindowBridge.open,
		//which settle also tests before running a turn, so a leftover shop dialog puts the new episode
		//into MENU for the same reason and with the same step-0 divergence.
		GameScene.clearHeadlessWindow();

		//The item an agent chose to use or drop, waiting for a slot or an aim. Read by
		//ActionMapper.resolveTarget as the fallback when no listener is armed.
		SlotAction.clearPendingUseItem();

		forgetPreviousRemains();
	}

	/**
	 * The file the game writes a dead hero's belongings to.
	 *
	 * <p>A literal rather than a reference, because {@code Bones.BONES_FILE} is private and adding a getter
	 * to expose it would be a change to the game for no benefit: this is the only file of the game's save
	 * data that is read during level generation rather than written during a save, and {@code
	 * HeadlessServices} redirects every other one into scratch.
	 */
	private static final String BONES_FILE = "bones.dat";

	/**
	 * Forgets any remains a previous run in this process left behind.
	 *
	 * <p>Both halves, or neither works: {@code Bones.clear()} drops what this process has cached, and the
	 * file is dropped because {@code Bones.get} reads it on its first call after a clear. A run that died
	 * in this process writes the file as it dies, so a statics-only fix would be undone by the next run's
	 * own death.
	 *
	 * <p>The file's path is resolved by {@link HeadlessServices.HeadlessFiles#deleteSave}, not assembled
	 * here. With saving disabled - which is how every rollout and every check runs - saves are redirected
	 * into a sink directory, so the obvious {@code root/bones.dat} is not where the file is and deleting it
	 * reports success having removed nothing.
	 *
	 * <p>The delete is best-effort and silent. It removes a file in a scratch directory this module owns,
	 * and a failure there has no bearing on the run - which is worth saying rather than logging, because a
	 * warning nobody can act on is noise and this file is not one.
	 */
	private static void forgetPreviousRemains(){
		Bones.clear();

		try {
			if (Gdx.files instanceof HeadlessServices.HeadlessFiles){
				((HeadlessServices.HeadlessFiles) Gdx.files).deleteSave( BONES_FILE );
			}
		} catch (RuntimeException ignored){
			//see above: a scratch file this module created, and nothing downstream depends on it
		}
	}
}