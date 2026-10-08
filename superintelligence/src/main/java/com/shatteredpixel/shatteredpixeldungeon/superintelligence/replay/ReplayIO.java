package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads and writes {@link Replay} files, and re-executes a recorded run to check it still reproduces.
 *
 * The format is line based text rather than a binary blob. Replays are small - a few thousand
 * decisions per run - and a format a human can read means a suspicious run can be diffed against a
 * known-good one by eye, which matters more here than the bytes saved.
 */
public class ReplayIO {

	private ReplayIO() {}

	// --------------------------------------------------------------------------- writing

	public static void write( Replay replay, File file ) throws IOException {
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null){
			//noinspection ResultOfMethodCallIgnored
			parent.mkdirs();
		}

		try (BufferedWriter out = Files.newBufferedWriter( file.toPath(), StandardCharsets.UTF_8 )){
			out.write( Replay.MAGIC );
			out.newLine();
			out.write( "version=" + Replay.VERSION );
			out.newLine();
			out.write( "seed=" + replay.seedText );
			out.newLine();
			out.write( "hero=" + replay.heroClass );
			out.newLine();
			out.write( "challenges=" + replay.challenges );
			out.newLine();
			out.write( "generation=" + replay.generation );
			out.newLine();
			out.write( "score=" + replay.score );
			out.newLine();
			out.write( "depth=" + replay.depth );
			out.newLine();
				out.write( "turns=" + replay.turns );
			out.newLine();
			out.write( "turn_limit=" + replay.turnLimitPerFloor );
			out.newLine();
			out.write( "max_slots=" + replay.maxSlots );
			out.newLine();
			out.write( "allow_equipping=" + replay.allowEquipping );
			out.newLine();
			out.write( "steps=" + replay.steps.size() );
			out.newLine();

			for (Replay.Step step : replay.steps){
				out.write( step.action );
				out.write( ' ' );
				out.write( Integer.toString( step.slot ));
				out.write( ' ' );
				out.write( step.mode );
				out.write( ' ' );
				out.write( Integer.toString( step.heroPos ));
				out.write( ' ' );
				out.write( Double.toString( step.reward ));
				//Sixth field, appended so recordings written before this field still parse: the
				//reader's length check already tolerates a short step line.
				out.write( ' ' );
				out.write( step.quickslots );
				//Fields 7-9. Absent on older recordings, which the length checks below tolerate.
				out.write( ' ' );
				out.write( Integer.toString( step.heroHp ));
				out.write( ' ' );
				out.write( Float.toString( step.turn ));
				out.write( ' ' );
				out.write( step.inventory );
				out.newLine();
			}
		}
	}

	// --------------------------------------------------------------------------- reading

	public static Replay read( File file ) throws IOException {
		//read whole file first: header lines are "key=value" and step lines are not, so the header
		//has to be told where it ends. Doing that on a live reader would swallow the first step.
		java.util.List<String> lines = Files.readAllLines( file.toPath(), StandardCharsets.UTF_8 );

		Replay replay = parseHeader( lines, file.getPath() );
		int declaredSteps = replay.declaredSteps;
		replay.steps.clear();
		int index = headerEnd( lines );

		for (int i = 0; i < declaredSteps; i++, index++ ){
			if (index >= lines.size()){
				throw new IOException( "Replay truncated after " + i + " of "
						+ declaredSteps + " steps" );
			}

			String line = lines.get( index );
			String[] parts = line.split( " " );
			if (parts.length < 3){
				throw new IOException( "Malformed replay step: " + line );
			}

			Replay.Step step = new Replay.Step();
			step.action = parts[ 0 ];
			step.slot = Integer.parseInt( parts[ 1 ] );
			step.mode = parts[ 2 ];
			if (parts.length > 3) step.heroPos = Integer.parseInt( parts[ 3 ] );
			if (parts.length > 4) step.reward = Double.parseDouble( parts[ 4 ] );
			//Absent on recordings made before quickslots were recorded. Such a recording cannot be played
			//back faithfully, because a slot index is meaningless without its bindings; the viewer says so
			//rather than silently resolving slot 0 to whatever happens to be first in the backpack.
			if (parts.length > 5) step.quickslots = parts[ 5 ];
			if (parts.length > 6) step.heroHp = Integer.parseInt( parts[ 6 ] );
			if (parts.length > 7) step.turn = Float.parseFloat( parts[ 7 ] );
			if (parts.length > 8) step.inventory = parts[ 8 ];
			replay.steps.add( step );
		}

		return replay;
	}

	/**
	 * Reads only the header, tolerating a truncated or absent body.
	 *
	 * {@link #read} rejects a file whose declared step count does not match its body, which is right
	 * for a determinism check and wrong for listing them: a truncated recording is exactly the one a
	 * person browsing a catalog most needs to see, and it will not fix itself by being hidden.
	 * {@link Replay#truncatedAt} carries the shortfall, or -1 when the body is complete.
	 */
	public static Replay readHeader( File file ) throws IOException {
		java.util.List<String> lines = Files.readAllLines( file.toPath(), StandardCharsets.UTF_8 );
		Replay replay = parseHeader( lines, file.getPath() );

		int available = Math.max( 0, lines.size() - headerEnd( lines ) );
		replay.truncatedAt = available < replay.declaredSteps ? available : -1;
		replay.steps.clear();

		return replay;
	}

	/** Index of the first step line, i.e. one past the end of the {@code key=value} header. */
	private static int headerEnd( java.util.List<String> lines ){
		int index = 1;
		while (index < lines.size() && lines.get( index ).indexOf( '=' ) >= 0) index++;
		return index;
	}

	private static Replay parseHeader( java.util.List<String> lines, String path ) throws IOException {
		Replay replay = new Replay();

		if (lines.isEmpty() || !Replay.MAGIC.equals( lines.get( 0 ))){
			throw new IOException( "Not a replay file: " + path );
		}

		for (int index = 1; index < lines.size(); index++ ){
			String line = lines.get( index );
			int eq = line.indexOf( '=' );
			if (eq < 0) break;

			String key = line.substring( 0, eq );
			String value = line.substring( eq + 1 );

			switch (key) {
				case "version":
					int version = Integer.parseInt( value );
					if (version > Replay.VERSION){
						throw new IOException( "Replay version " + version
								+ " is newer than this build understands (" + Replay.VERSION + ")" );
					}
					break;
				case "seed":       replay.seedText = value; break;
				case "hero":       replay.heroClass = value; break;
				case "challenges": replay.challenges = Integer.parseInt( value ); break;
				case "generation": replay.generation = Integer.parseInt( value ); break;
				case "score":      replay.score = Double.parseDouble( value ); break;
				case "depth":      replay.depth = Integer.parseInt( value ); break;
				case "turns":      replay.turns = Integer.parseInt( value ); break;
				case "turn_limit": replay.turnLimitPerFloor = Integer.parseInt( value ); break;
				//Absent in version 1, which then keeps EnvConfig's defaults - see Replay.VERSION.
				case "max_slots": replay.maxSlots = Integer.parseInt( value ); break;
				case "allow_equipping": replay.allowEquipping = Boolean.parseBoolean( value ); break;
				case "steps":      replay.declaredSteps = Integer.parseInt( value ); break;
				default: break;
			}
		}

		return replay;
	}

	/**
	 * The {@link EnvConfig} a replay has to be re-executed under.
	 *
	 * <p>The single place the header is turned back into settings, so the viewer, {@code verify} and the
	 * new headless verifier cannot each pick their own: the point of recording these is that a replay
	 * resolves a slot index the same way the recorder did, and three callers guessing independently is
	 * how that stops being true.
	 */
	public static EnvConfig configFor( Replay replay ){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = replay.turnLimitPerFloor;
		config.maxSlots = replay.maxSlots;
		config.allowEquipping = replay.allowEquipping;
		return config;
	}

	// --------------------------------------------------------------------------- verification

	/** What re-executing a recorded run observed. */
	public static class Verification {
		public double score;
		public int depth;
		public int turns;
		public int stepsVerified;
		public boolean diverged;
		public int divergedAt = -1;

		/** What disagreed, naming the quantity. Empty when the run reproduced. */
		public String divergence = "";
	}

	/**
	 * Re-executes a recorded run and reports whether it reproduced.
	 *
	 * <p>This is a determinism regression check, not a playback. Nothing is drawn and no one is
	 * watching - the value is in catching the moment the recorded path stops matching, because every
	 * locked-seed comparison in the trainer becomes meaningless at that point.
	 *
	 * Used to verify that a recorded run still reproduces. It does, as long as the seed, the hero
	 * class and the challenge set all match: the game's per-floor RNG is derived purely from
	 * {@code Dungeon.seed}, depth and branch, so a floor is a function of the seed alone.
	 *
	 * Any divergence is reported rather than hidden, because the moment a replay stops
	 * reproducing is the moment the seed lock has been broken somewhere and every locked-seed
	 * comparison in the trainer becomes meaningless.
	 */
	public static Verification verify( Replay replay, SPDEnv env ){
		return verify( replay, env, null );
	}

	/** Notified once per replayed step, after the world has settled. */
	public interface StepObserver {
		/**
		 * Called once the world is built and before the first step, so an observer measuring cumulative
		 * counters can start from zero.
		 *
		 * <p>Level generation draws several thousand values, and folding them in would make every early
		 * step look identical while saying nothing about the steps themselves. Determinism of generation
		 * is already covered by the position comparison below.
		 */
		default void onReset(){}

		void onStep( int index, Replay.Step step, boolean running );
	}

	/**
	 * Replays, notifying {@code observer} after each step.
	 *
	 * <p>The observer is how {@code rngtrace} reuses this loop rather than writing a second one. A second
	 * replay loop would be a second implementation of "replay a recording", which is the same
	 * duplication that let the viewer and the trainer drift apart in the first place.
	 */
	public static Verification verify( Replay replay, SPDEnv env, StepObserver observer ){
		Verification result = new Verification();

		HeroClass heroClass;
		try {
			heroClass = HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			heroClass = HeroClass.WARRIOR;
		}

		//every setting the header carries, not just the turn cap: the slot width and the
		//equipping rule are both part of what a recorded index meant at the time.
		EnvConfig replayed = configFor( replay );
		env.config().turnLimitPerFloor = replayed.turnLimitPerFloor;
		env.config().maxSlots = replayed.maxSlots;
		env.config().allowEquipping = replayed.allowEquipping;
		env.reset( replay.seedText, heroClass );
		if (observer != null) observer.onReset();

		int cumulative = 0;
		for (int i = 0; i < replay.steps.size(); i++){
			if (!env.running()) break;

			Replay.Step step = replay.steps.get( i );
			Action action = Action.valueOf( step.action );

			cumulative += env.step( action, step.slot );
			result.stepsVerified++;

			//Each comparison below is against something the recording carries, not against another run of
			//this code. Re-executing a replay through the same headless path only proves the engine is
			//deterministic; it cannot see a headless-specific fault, because both sides share it. That is
			//how a run whose hunger clock never advanced - the intro setting froze Hunger.act() - replayed
			//perfectly in the viewer while being wrong from step 454 on, with the hero at full health in
			//the recording and dying in the game.
			//
			//So the recorded state is the authority: position, health, engine time and inventory. Each
			//catches a class the others cannot see.
			if (step.heroPos >= 0 && env.heroPosition() != step.heroPos ){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = "position: replayed " + env.heroPosition() + ", recorded " + step.heroPos;
				break;
			}

			if (step.heroHp >= 0 && Dungeon.hero.HP != step.heroHp ){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = "hp: replayed " + Dungeon.hero.HP + ", recorded " + step.heroHp;
				break;
			}

			//A turn is a duration and can be fractional, so this is compared exactly rather than rounded.
			//It is what catches a step that spends a turn the recording says it did not: the two
			//environments were one turn apart from step 2 onwards while identical in position.
			if (step.turn >= 0 && Actor.now() != step.turn ){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = "turn: replayed " + Actor.now() + ", recorded " + step.turn;
				break;
			}

			if (!step.inventory.isEmpty() && !step.inventory.equals( ReplayRecorder.inventory() )){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = "inventory: replayed [" + ReplayRecorder.inventory()
						+ "], recorded [" + step.inventory + "]";
				break;
			}

			//reported after the comparisons, so a step that diverged is still traced: the point of a
			//trace is often to see what the world was doing at the step where it stopped agreeing.
			//One sample per replayed step and no trailing sentinel, so a trace lines up against one
			//collected by the viewer step for step.
			if (observer != null) observer.onStep( i, step, true );
		}

		result.score = env.ledger().total();
		result.depth = env.depth();
		result.turns = env.turnsTotal();
		return result;
	}
}
