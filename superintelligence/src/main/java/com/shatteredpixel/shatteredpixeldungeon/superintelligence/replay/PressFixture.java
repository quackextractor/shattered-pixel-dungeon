package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.File;
import java.io.IOException;

/**
 * A short recording in which a thrown item lands on something a press can fire, for the viewer gates to
 * play.
 *
 * <p><b>The corpus cannot witness a press, and that is a hole rather than a fact about the game.</b>
 * {@code Level.drop} presses the cell an item lands on, which is how a thrown stone sets off a trap or
 * flattens grass - and behind a scene test that no renderer-bypassed process could satisfy, so nothing
 * was ever pressed and nothing in any recording ever could have noticed. {@code presscheck} proves the
 * press happens; only a recording proves the two environments now <em>agree</em> about it, which is a
 * different claim and the one a trainer whose runs are watched has to be able to make.
 *
 * <p>The throw is driven through the agent's own three steps - {@code USE}, {@code USE} on the slot,
 * {@code USE} on the target - rather than by calling {@code Item.throwAt} directly, so the recording
 * carries the aim the way a real run does and playback resolves it through the same
 * {@code Quickslots.prepare} every other slot step goes through.
 *
 * <p>DUELIST starts with a {@code ThrowingSpike}, which is the item that makes this possible: it
 * {@code usesTargeting}, so it is thrown rather than equipped, and it is bound to a quickslot.
 *
 * <p>The seed is searched rather than fixed, because the fixture needs a high-grass cell beside the hero
 * and no single floor can be relied on to have one. A seed whose throw did not actually press is not
 * recorded: a fixture that covers nothing would pass every viewer gate while being useless, which is the
 * failure mode this class exists to close.
 */
public final class PressFixture {

	private PressFixture() {}

	/** Short enough to play in a gate, long enough that the run has a shape. */
	private static final int STEPS = 60;

	private static final String[] SEEDS = {
			"PRESS-THROW-A", "PRESS-THROW-B", "PRESS-THROW-C", "PRESS-THROW-D", "PRESS-THROW-E",
			"PRESS-THROW-F", "PRESS-THROW-G", "PRESS-THROW-H"
	};

	private static final HeroClass HERO = HeroClass.DUELIST;

	/**
	 * Records the fixture and writes it.
	 *
	 * @return the recording, so a caller can verify it without re-reading the file
	 * @throws IOException if the file cannot be written
	 * @throws IllegalStateException if no seed produced a throw that pressed, which would make the
	 *                               fixture a recording of nothing
	 */
	public static Replay recordAndWrite( EnvConfig config, File file ) throws IOException {
		Replay replay = record( config );
		ReplayIO.write( replay, file );
		return replay;
	}

	/** Records the fixture, without writing it anywhere. */
	public static Replay record( EnvConfig config ){
		for (String seed : SEEDS){
			Replay recorded = attempt( config, seed );
			if (recorded != null) return recorded;
		}
		throw new IllegalStateException( "no seed gave the hero a throwable beside high grass on floor 1,"
				+ " so the recording would contain no throw that presses anything." );
	}

	/**
	 * One seed's run, or null when this floor cannot make the case.
	 *
	 * <p>Returns null rather than recording a run whose throw landed on bare floor: that is a recording
	 * the viewer gates would play happily and which would cover nothing, so it is not worth having.
	 */
	private static Replay attempt( EnvConfig config, String seed ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HERO );

		int slot = throwableSlot( env );
		int target = grassTarget( env );
		if (slot < 0 || target < 0) return null;

		int grass = env.mapper().targetCell( target );
		int before = Dungeon.level.map[grass];

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HERO.name(), 0, config.turnLimitPerFloor, config );
		Replay replay = recorder.replay();

		step( env, recorder, Action.USE, 0, EnvMode.WORLD );
		step( env, recorder, Action.USE, slot, EnvMode.SLOT );

		//The SLOT step's SlotAction.use reports whether an aim is needed, and that answer is what puts
		//the environment in TARGETING. A throwable whose use declined would leave it in WORLD, and the
		//third step below would then be recorded as a target choice against a mode it was never in -
		//which reads as a divergence on verify rather than as a seed this fixture cannot use.
		if (env.mode() != EnvMode.TARGETING) return null;

		step( env, recorder, Action.USE, target, EnvMode.TARGETING );

		if (Dungeon.level.map[grass] == Terrain.HIGH_GRASS) return null;

		System.out.println( "  the throw pressed its landing cell: " + seed + ", "
				+ env.mapper().slot( slot ).getClass().getSimpleName() + " thrown at cell " + grass
				+ ", terrain " + before + " -> " + Dungeon.level.map[grass] );

		int[] secondary = new int[ 1 ];
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 3 );
		while (env.running() && replay.length() < STEPS){
			Action action = policy.choose( env, secondary );
			step( env, recorder, action, secondary[ 0 ], env.mode() );
		}

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );
		return replay;
	}

	/** The first slot holding a bound, unequipped, aimed item, or -1. */
	private static int throwableSlot( SPDEnv env ){
		env.mapper().refreshSlots();
		for (int i = 0; i < env.mapper().slots().size(); i++){
			Item item = env.mapper().slot( i );
			if (item == null || !item.usesTargeting) continue;
			if (Dungeon.quickslot.getSlot( item ) >= 0 && !item.isEquipped( Dungeon.hero )) return i;
		}
		return -1;
	}

	/**
	 * The target index naming an adjacent cell of high grass, or -1.
	 *
	 * <p>Empty of everything a throw would interact with instead of the grass: a heap or a plant is a
	 * different object in the way, and a mob standing there takes the hit rather than the ground.
	 */
	private static int grassTarget( SPDEnv env ){
		for (int o = 1; o < ActionMapper.TARGET_COUNT; o++){
			int cell = env.mapper().targetCell( o );
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (Dungeon.level.map[cell] != Terrain.HIGH_GRASS) continue;
			if (Dungeon.level.heaps.containsKey(cell) || Dungeon.level.plants.containsKey(cell)) continue;
			if (Actor.findChar( cell ) != null) continue;
			return o;
		}
		return -1;
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
