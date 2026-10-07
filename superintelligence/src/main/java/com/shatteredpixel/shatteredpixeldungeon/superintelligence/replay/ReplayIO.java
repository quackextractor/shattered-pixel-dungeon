package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
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
				case "steps":      replay.declaredSteps = Integer.parseInt( value ); break;
				default: break;
			}
		}

		return replay;
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
		Verification result = new Verification();

		HeroClass heroClass;
		try {
			heroClass = HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			heroClass = HeroClass.WARRIOR;
		}

		env.config().turnLimitPerFloor = replay.turnLimitPerFloor;
		env.reset( replay.seedText, heroClass );

		int cumulative = 0;
		for (int i = 0; i < replay.steps.size(); i++){
			if (!env.running()) break;

			Replay.Step step = replay.steps.get( i );
			Action action = Action.valueOf( step.action );

			cumulative += env.step( action, step.slot );
			result.stepsVerified++;

			//the recorded position is where the hero ended up, so this is an exact check that
			//the replayed run is following the same path
			if (step.heroPos >= 0 && env.heroPosition() != step.heroPos ){
				result.diverged = true;
				result.divergedAt = i;
				break;
			}
		}

		result.score = env.ledger().total();
		result.depth = env.depth();
		result.turns = env.turnsTotal();
		return result;
	}
}
