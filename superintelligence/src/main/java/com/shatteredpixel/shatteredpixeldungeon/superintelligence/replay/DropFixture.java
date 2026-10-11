package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.File;
import java.io.IOException;

/**
 * A short recording that contains a {@code DROP} in {@link EnvMode#SLOT}, for the viewer gates to play.
 *
 * <p><b>The committed corpus has no {@code DROP} in it, and that is a hole rather than a fact about the
 * game.</b> Measured across the 17 recordings in {@code test-replays}: 310 SLOT-mode steps, and the
 * entire SLOT vocabulary is {@code USE/SLOT}. {@code ScriptedPolicy} never chooses {@code DROP}, and the
 * policy that writes the trainer's recordings is untrained, so nothing produced one by accident either.
 *
 * <p>{@code DROP} is the one action that unbinds its own item, which makes it the step where the instant a
 * recording pins the quickslot bindings stops being a detail: captured afterwards, the dropped item is
 * already out of the backpack and its binding is missing from the file, and playback resolves the same
 * index to a different item. On {@code MSU-OTL-OAT} that is step 17 - a ThrowingSpike headlessly and a
 * Food in the viewer - and every gate passed, because the thing they would have caught was absent rather
 * than because anything was caught.
 *
 * <p>Recorded through {@link ReplayRecorder} in the order the worker uses, so this fixture exercises the
 * same seam rather than a convenient one: {@code record()} before the step, which is where the bindings
 * are captured, and {@code afterStep()} after it. DUELIST is the class that makes the case sharpest,
 * because its starting bindings straddle both halves of the question - the {@code Rapier} is equipped and
 * bound to quickslot 0, which no slot index can name, and the {@code ThrowingSpike} is carried and bound
 * to quickslot 1, which one can.
 */
public final class DropFixture {

	private DropFixture() {}

	/** Short enough to play in a gate, long enough that the run has a shape. */
	private static final int STEPS = 60;

	/** Inside the game's 20 character custom-seed cap, which it enforces rather than warns about. */
	private static final String SEED = "DROP-IN-SLOT";

	private static final HeroClass HERO = HeroClass.DUELIST;

	/**
	 * Records the fixture and writes it.
	 *
	 * @return the recording, so a caller can verify it without re-reading the file
	 * @throws IOException    if the file cannot be written
	 * @throws IllegalStateException if the run did not reach a drop, which would make the fixture a lie
	 */
	public static Replay recordAndWrite( EnvConfig config, File file ) throws IOException {
		Replay replay = record( config );
		ReplayIO.write( replay, file );
		return replay;
	}

	/** Records the fixture, without writing it anywhere. */
	public static Replay record( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HERO );

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HERO.name(), 0, config.turnLimitPerFloor, config );
		Replay replay = recorder.replay();

		//the first slot holding a bound, unequipped item - the one whose resolution the bindings decide,
		//which on a DUELIST is the ThrowingSpike rather than the Food an unbound list would give
		env.mapper().refreshSlots();
		int slot = -1;
		for (int i = 0; i < env.mapper().slots().size(); i++){
			Item item = env.mapper().slot( i );
			if (item == null) continue;
			if (Dungeon.quickslot.getSlot( item ) >= 0 && !item.isEquipped( Dungeon.hero )){
				slot = i;
				break;
			}
		}
		if (slot < 0){
			throw new IllegalStateException( SEED + " carries nothing bound and unequipped, so the"
					+ " fixture cannot drop an item whose slot resolution the bindings decide." );
		}

		//two steps, because that is what a slot action costs: one to choose it and one to say which
		step( env, recorder, Action.USE, 0, EnvMode.WORLD );
		step( env, recorder, Action.DROP, slot, EnvMode.SLOT );

		int[] secondary = new int[ 1 ];
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 3 );
		while (env.running() && recorder.replay().length() < STEPS){
			Action action = policy.choose( env, secondary );
			step( env, recorder, action, secondary[ 0 ], env.mode() );
		}

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );
		return replay;
	}

	/**
	 * One step, recorded the way {@code Worker}'s listener records one.
	 *
	 * <p>{@code record} before {@code env.step} and {@code afterStep} after it, because the bindings are
	 * the world the slot index was chosen in and everything else is the world the step produced.
	 */
	private static void step( SPDEnv env, ReplayRecorder recorder, Action action, int secondary,
			EnvMode mode ){
		recorder.record( action, secondary, mode );
		float reward = (float) env.step( action, secondary );
		recorder.afterStep( env.heroPosition(), reward );
	}
}
