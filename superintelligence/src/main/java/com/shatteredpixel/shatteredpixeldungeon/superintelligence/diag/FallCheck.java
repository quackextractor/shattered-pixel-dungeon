package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.watabou.utils.PathFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails if a chasm fall is dropped, lands the hero somewhere the game would not, or inherits an
 * earlier episode.
 *
 * <pre>gradle :superintelligence:fallcheck</pre>
 *
 * <p>A fall is the one floor transition the game does not route through a {@code LevelTransition}.
 * {@code Chasm.heroFall} writes {@code InterlevelScene.mode}, asks for a scene switch, and writes
 * nothing else - no {@code curTransition}, no destination, no cell. Everything after that is the
 * receiving end's problem, and the receiving end here is {@link
 * com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline}, so this gate is
 * about what it does with a request that arrives with nothing in it.
 *
 * <p><b>What it was.</b> {@code handleTransition} tested {@code curTransition == null} before it
 * tested {@code mode == FALL}, and on a fall {@code curTransition} is null <i>by construction</i> — so
 * the null test fired first, the method answered "not a level transition", cleared the switch request
 * and dropped the fall, and {@code Transition.FALL} and {@code LevelPipeline.handleFall} were
 * unreachable code. Measured on three seeds: a hero put on a chasm cell and stepped stayed on depth 1
 * with the switch acknowledged.
 *
 * <p>Nothing in the suite drove one, and {@code transitioncheck}'s nine cases are all stair
 * transitions, which do carry a {@code curTransition}.
 */
public class FallCheck {

	private static final int CHECKS = 5;

	/** Seeds searched for a floor with a chasm the hero can be put next to. */
	private static final String[] SEEDS = {
			"FALLCHECK-A", "FALLCHECK-B", "FALLCHECK-C", "FALLCHECK-D",
			"FALLCHECK-E", "FALLCHECK-F", "FALLCHECK-G", "FALLCHECK-H"
	};

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-fallcheck" ));
		HeadlessServices.disableSaving( true );

		checkAFallIsServiced();
		checkTheFallHurts();
		checkAnEpisodeDoesNotInheritTheLastEpisodesFall();
		checkWalkingIntoAChasmDoesNotTakeTheProcessDown();
		checkTheSameFallTwiceIsTheSameFall();

		if (failures.isEmpty()){
			System.out.println( "[OK]     chasm falls: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  chasm falls: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	// --------------------------------------------------------------------------- fixtures

	private static SPDEnv env(){
		EnvConfig config = new EnvConfig();
		return new SPDEnv( config, HeadlessGame.install() );
	}

	/** A chasm cell with a plain floor cell beside it, or -1 when this floor has no chasm. */
	private static int pitBeside( int[] beside ){
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (!Dungeon.level.pit[c] || Dungeon.level.solid[c]) continue;
			for (int d : PathFinder.NEIGHBOURS8){
				int n = c + d;
				if (n < 0 || n >= Dungeon.level.length()) continue;
				if (!Dungeon.level.insideMap(n)) continue;
				if (!Dungeon.level.passable[n] || Dungeon.level.pit[n] || Dungeon.level.solid[n]) continue;
				if (Actor.findChar(n) != null) continue;
				beside[0] = n;
				return c;
			}
		}
		return -1;
	}

	/**
	 * Drops the hero onto a chasm the way the engine does.
	 *
	 * <p>Placed on the cell and handed to {@code Level.occupyCell}, which is the call {@code
	 * Char.move} makes and the one {@code Chasm.heroLand} makes. Going through the engine rather than
	 * through {@code Chasm.heroFall} directly is what makes this a test of the fall and not of a
	 * method the gate itself called.
	 */
	private static boolean drop( SPDEnv env ){
		int[] beside = new int[1];
		int pit = pitBeside( beside );
		if (pit < 0) return false;

		Dungeon.hero.pos = pit;
		Dungeon.hero.flying = false;
		Dungeon.hero.curAction = null;
		Dungeon.hero.ready = false;

		Dungeon.level.occupyCell( Dungeon.hero );
		env.step( Action.WAIT, 0 );
		return true;
	}

	// --------------------------------------------------------------------------- checks

	/**
	 * A fall is a floor.
	 *
	 * <p>Asserted on the world rather than on a return value: {@code handleTransition}'s verdict was
	 * the thing that was wrong, so asserting on it would pass against the fault it was written for.
	 * Depth, a living hero and a clock that restarted with the floor are what a fall costs when it
	 * does not happen.
	 */
	private static void checkAFallIsServiced(){
		SPDEnv env = env();

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );
			int before = Dungeon.depth;

			if (!drop( env )) continue;

			if (Dungeon.depth != before + 1){
				fail( seed + ": the hero was dropped onto a chasm on depth " + before + " and is still on"
						+ " depth " + Dungeon.depth + ". A chasm fall is the one transition that carries no"
						+ " LevelTransition - Chasm.heroFall writes InterlevelScene.mode and nothing else - so"
						+ " handleTransition has to read the mode before it reads the transition. It read"
						+ " curTransition == null first, which is true on every fall, and answered 'not a level"
						+ " transition'. Transition.FALL and LevelPipeline.handleFall were unreachable."
						+ " Measured on three seeds before the fix: depth unchanged, switch request cleared."
						+ " This is issues.md 16's subject - a run that falls and a run that does not describe"
						+ " different dungeons from the same seed." );
				return;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				fail( seed + ": the fall arrived on depth " + Dungeon.depth + " with no living hero." );
				return;
			}

			if (Dungeon.level.pit[ Dungeon.hero.pos ]){
				fail( seed + ": the fall landed the hero on cell " + Dungeon.hero.pos + ", which is itself a"
						+ " chasm. Level.fallCell only returns a passable cell and a PitRoom's floor is"
						+ " passable, so landing on one means the cell came from somewhere else." );
				return;
			}

			if (Actor.now() > 1f ){
				fail( seed + ": engine time is " + Actor.now() + " on arrival at depth " + Dungeon.depth
						+ " after a fall from depth " + before + ", so the new floor did not restart the"
						+ " clock the way Dungeon.newLevel() restarts it everywhere else." );
				return;
			}

			System.out.println( "  a fall is a floor: " + seed + " depth " + before + " -> " + Dungeon.depth
					+ ", hero alive at " + Dungeon.hero.pos + " on engine time " + Actor.now() );
			return;
		}

		fail( "no seed on the list generated a chasm with a floor cell beside it, so this case measured"
				+ " nothing. Every regular floor generates special rooms and a chasm feeling puts one on"
				+ " most of them." );
	}

	/**
	 * Landing hurts.
	 *
	 * <p>{@code InterlevelScene.fall} opens with {@code Buff.affect(hero, Chasm.Falling.class)}, whose
	 * act is {@code Chasm.heroLand} - fall damage, cripple, bleeding, and a press of the landing cell.
	 * The headless path skipped all of it, so a fall was the cheapest way to lose a floor: strictly
	 * better than the stairs, which cost nothing here but cost a hero's health in the game.
	 */
	private static void checkTheFallHurts(){
		SPDEnv env = env();

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );
			int[] beside = new int[1];
			if (pitBeside( beside ) < 0) continue;

			int before = Dungeon.hero.HP;
			if (!drop( env )) continue;

			if (Dungeon.hero.isAlive() && Dungeon.hero.HP >= before){
				fail( seed + ": the hero fell from depth 1 to depth " + Dungeon.depth + " and arrived on "
						+ before + " health, unchanged. InterlevelScene.fall() applies Chasm.Falling before"
						+ " it builds the floor, and that buff's act is Chasm.heroLand(), which damages him."
						+ " A fall that costs nothing is a strictly better way down than the stairs." );
				return;
			}

			System.out.println( "  landing hurts: " + seed + " fell from depth 1 and arrived on "
					+ ( Dungeon.hero.isAlive() ? Dungeon.hero.HP + "/" + Dungeon.hero.HT
					+ " health" : "the floor dead" ) );
			return;
		}

		fail( "no seed produced a survivable fall, so this case measured nothing." );
	}

	/**
	 * One run must not inherit the last one's fall.
	 *
	 * <p>{@code InterlevelScene.mode} is a process static, and nothing headless ever resets it - a real
	 * game gets a fresh {@code InterlevelScene} per descent, which is where its mode comes from. Once
	 * {@code handleTransition} reads the mode before the null transition, it becomes load-bearing: a
	 * {@code Mode.FALL} left behind by one episode is read by the next episode's first ordinary
	 * descent, which is then serviced as a fall - one floor down and a random cell, for a hero who
	 * walked down the stairs.
	 *
	 * <p>Asserted twice, because the two halves fail differently. The first is that a serviced fall
	 * leaves nothing behind, which is the property and the mutation. The second stages the leftover
	 * directly, because a process can arrive holding one by other means and a reset still has to drop
	 * it.
	 *
	 * <p>This is the shape {@code issues.md} 16 measured and could not attribute - "the divergence is
	 * not seed-determined, it needs a worker that has already run other episodes" - on the one field
	 * that decides how a transition is serviced.
	 */
	private static void checkAnEpisodeDoesNotInheritTheLastEpisodesFall(){
		SPDEnv env = env();
		env.reset( SEEDS[0], HeroClass.WARRIOR );

		if (pitBeside( new int[1] ) < 0 ){
			fail( "floor 1 of " + SEEDS[0] + " has no chasm beside a floor cell, so there is no fall to"
					+ " leave behind. This case measured nothing." );
			return;
		}
		if (!drop( env )){
			fail( "the fall did not happen, so there is nothing to inherit. This case measured nothing." );
			return;
		}

		if (InterlevelScene.mode != null || InterlevelScene.curTransition != null){
			fail( "a serviced fall left InterlevelScene.mode=" + InterlevelScene.mode + " and curTransition="
					+ InterlevelScene.curTransition + " behind. LevelPipeline.clearSwitchRequest is where the"
					+ " hand-off is acknowledged and it already dropped curTransition; mode is the third field"
					+ " of the same hand-off and is the one handleTransition reads first, so a stale FALL turns"
					+ " the next ordinary descent in this process into a fall. Measured before the fix: a run"
					+ " following a fall that descended landed on a random cell of the floor below instead of"
					+ " its exit." );
			return;
		}

		//and the leftover anyway, staged, because a reset has to drop one from any source
		InterlevelScene.mode = InterlevelScene.Mode.FALL;
		env.reset( SEEDS[1], HeroClass.WARRIOR );

		if (InterlevelScene.mode == InterlevelScene.Mode.FALL){
			fail( "a reset left InterlevelScene.mode at FALL. It is process state that belongs to no run,"
					+ " and the next transition in this process is read through it." );
			return;
		}

		LevelTransition exit = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_EXIT );
		if (exit == null ){
			fail( "floor 1 of " + SEEDS[1] + " has no REGULAR_EXIT, so the descent this case is about was"
					+ " never attempted. Every standard floor generates one." );
			return;
		}

		Dungeon.hero.pos = exit.cell();
		boolean accepted = Dungeon.level.activateTransition( Dungeon.hero, exit );
		//the cell a descent lands the hero on, which handleTransition resolves through destType before
		//the floor is built. Captured here rather than recomputed afterwards because getTransition is
		//asked of the *new* level, which does not exist yet.
		int destType = InterlevelScene.curTransition.destType.ordinal();
		Dungeon.hero.ready = false;
		env.step( Action.WAIT, 0 );

		if (!accepted){
			fail( "the level refused the transition, so the descent this case is about never started." );
			return;
		}

		if (Dungeon.depth != 2){
			fail( "after a fall, a new run descended and is on depth " + Dungeon.depth + " rather than 2."
					+ " InterlevelScene.mode is a process static that a real game resets by building a fresh"
					+ " InterlevelScene for every descent and that nothing headless resets, so the mode the"
					+ " previous episode's fall left behind is read by this one's descent and turns it into"
					+ " a fall. The recording of that run would describe a hero who walked down the stairs and"
					+ " found himself on a random cell of the floor below." );
			return;
		}

		LevelTransition.Type expectedType = LevelTransition.Type.values()[ destType ];
		LevelTransition arrival = Dungeon.level.getTransition( expectedType );
		if (arrival == null || Dungeon.hero.pos != arrival.cell()){
			fail( "the hero descended and arrived on cell " + Dungeon.hero.pos + " rather than on depth 2's "
					+ expectedType + " (" + ( arrival == null ? -1 : arrival.cell() ) + "), which is where"
					+ " LevelPipeline.handleTransition places him and is not where Level.fallCell would."
					+ " The descent was serviced as a fall." );
			return;
		}

		System.out.println( "  a fall leaves no hand-off behind, and a descent after one lands on depth 2's "
				+ expectedType + " (" + Dungeon.hero.pos + ")" );
	}

	/**
	 * Walking toward a chasm asks a question, and asking it must not take the process down.
	 *
	 * <p>Measured before the fix: {@code NullPointerException} from
	 * {@code new Image(Dungeon.level.tilesTex(), 176, 16, 16, 16)}. {@code Level.tilesTex} names a
	 * texture, {@code TextureCache} returns nothing for it with no renderer, and the five-argument
	 * {@code Image} constructor derived its frame rectangle from the texture without the guard
	 * {@code frame(RectF)} had carried since the bypass landed. It is thrown from inside
	 * {@code Actor.headlessStep}, and nothing between there and {@code Worker.serve} catches it, so one
	 * hero stepping at a chasm ended a worker's episode.
	 */
	private static void checkWalkingIntoAChasmDoesNotTakeTheProcessDown(){
		SPDEnv env = env();

		boolean walked = false;

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );

			int[] beside = new int[1];
			int pit = pitBeside( beside );
			if (pit < 0) continue;

			Dungeon.hero.pos = beside[0];
			Dungeon.hero.curAction = null;
			Dungeon.hero.ready = false;

			Action into = null;
			for (Action a : Action.values()){
				if (!a.directional()) continue;
				if (beside[0] + a.dx + a.dy * Dungeon.level.width() == pit){ into = a; break; }
			}
			if (into == null ) continue;

			int depth = Dungeon.depth;
			try {
				env.step( into, 0 );
			} catch (RuntimeException e){
				fail( seed + ": walking the hero into a chasm threw " + e.getClass().getSimpleName() + ": "
						+ e.getMessage() + ". The game's own \"jump?\" prompt builds its icon with"
						+ " new Image(Dungeon.level.tilesTex(), ...), and with no renderer that texture is"
						+ " null, so the five-argument Image constructor dereferenced it. It is thrown from"
						+ " inside Actor.headlessStep and nothing between there and Worker.serve catches it,"
						+ " so the episode - and, in a training run, the worker - ended on it." );
				return;
			}

			if (!env.running()){
				fail( seed + ": walking the hero into a chasm ended the episode as " + env.endReason()
						+ ". The chasm is a question the game asks, not a death." );
				return;
			}

			if (Dungeon.depth != depth && !WindowBridge.open()){
				fail( seed + ": the hero walked toward a chasm and the floor changed from depth " + depth
						+ " to " + Dungeon.depth + ". The game asks before it drops anyone." );
				return;
			}

			if (WindowBridge.open()){
				//answer it the way an agent would, then require the environment to still be playable
				WindowBridge.selectOption( 0 );
				env.step( Action.WAIT, 0 );
				if (!env.running()){
					fail( seed + ": the jump prompt was opened, answered, and the episode ended as "
							+ env.endReason() + " rather than carrying on." );
					return;
				}
			}

			walked = true;
			break;
		}

		if (!walked){
			fail( "no seed produced a chasm the hero could be put next to and asked to step into, so this"
					+ " case measured nothing." );
			return;
		}

		System.out.println( "  walking toward a chasm asks the game's question instead of ending the process" );
	}

	/**
	 * The same fall twice, in one process, is the same fall.
	 * <p>This is the property {@code issues.md} 16 is about and the one nothing above it can establish:
	 * that where a hero lands is a function of the seed he fell from and of the floor he fell through,
	 * rather than of whatever the process did before. {@code Level.fallCell} draws from the base
	 * generator, so a fall is one of the few places where the landing cell depends on the RNG stream's
	 * position - and {@code SpecialRoom.runSpecials}, the pool the destination floor draws its rooms
	 * from, is itself consumed as floors are generated.
	 *
	 * <p>Driven by a trace rather than by a recording, because what is at stake is the two halves of one
	 * event: the floor the hero left and the floor he landed on. Asserted on depth, cell, health and
	 * the engine clock together, so a difference in any of them is a difference in the fall.
	 */
	private static void checkTheSameFallTwiceIsTheSameFall(){
		EnvConfig config = new EnvConfig();
		SPDEnv first = new SPDEnv( config, HeadlessGame.install() );

		String seed = null;
		String[] before = null;
		for (String candidate : SEEDS){
			first.reset( candidate, HeroClass.WARRIOR );
			int depthFrom = Dungeon.depth;
			if (pitBeside( new int[1] ) < 0 ) continue;
			if (!drop( first )) continue;
			if (Dungeon.depth != depthFrom + 1){
				fail( candidate + ": the hero was dropped onto a chasm on depth " + depthFrom
						+ " and is still on depth " + Dungeon.depth + ", so comparing the fall to itself would"
						+ " measure nothing. See the first case." );
				return;
			}
			seed = candidate;
			before = new String[]{ String.valueOf( depthFrom ), state() };
			break;
		}

		if (seed == null ){
			fail( "no seed produced a fall, so this case measured nothing." );
			return;
		}

		//churn: other runs in between, which is what a worker does between two episodes
		SPDEnv churn = new SPDEnv( config, HeadlessGame.install() );
		for (String other : SEEDS){
			if (!other.equals( seed )) churn.reset( other, HeroClass.WARRIOR );
		}

		SPDEnv second = new SPDEnv( config, HeadlessGame.install() );
		second.reset( seed, HeroClass.WARRIOR );
		if (!drop( second )){
			fail( "the fall did not reproduce at all on the second visit to " + seed + "." );
			return;
		}

		String after = state();
		if (!before[1].equals( after )){
			fail( "the same fall twice in one process landed differently. First: " + before[1]
					+ " (from depth " + before[0] + "). Second: " + after + ". A fall places the hero with"
					+ " Level.fallCell, which draws from the base generator, and the destination floor's"
					+ " special rooms come out of SpecialRoom.runSpecials, which is consumed as floors are"
					+ " generated - so anything the process carries between episodes shows up here and"
					+ " nowhere earlier. This is issues.md 16's shape: a divergence that needs a worker which"
					+ " has already run other episodes." );
			return;
		}

		System.out.println( "  the same fall twice is the same fall: " + after );
	}

	/** Depth, cell, health, clock and floor shape, as one comparable string. */
	private static String state(){
		StringBuilder sb = new StringBuilder();
		sb.append( "depth " ).append( Dungeon.depth )
				.append( " pos " ).append( Dungeon.hero.pos )
				.append( " hp " ).append( Dungeon.hero.HP ).append( '/' ).append( Dungeon.hero.HT )
				.append( " now " ).append( Actor.now() )
				.append( " floor " ).append( Dungeon.level.width() ).append( 'x' )
				.append( Dungeon.level.height() )
				.append( " runSpecials " ).append( runSpecials() );
		return sb.toString();
	}

	private static String runSpecials(){
		StringBuilder sb = new StringBuilder();
		for (Object room : com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom
				.runSpecials){
			sb.append( room instanceof Class ? ((Class<?>) room).getSimpleName() : String.valueOf( room ) )
					.append( ',' );
		}
		return sb.toString();
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
