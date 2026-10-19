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


public class ReplayIO {

	private ReplayIO() {}

	public static void write( Replay replay, File file ) throws IOException {
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null){

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
			out.write( "turn_limit_total=" + replay.turnLimitTotal );
			out.newLine();
			out.write( "stall_limit=" + replay.stallLimit );
			out.newLine();
			out.write( "free_step_budget=" + replay.freeStepBudget );
			out.newLine();
			out.write( "actor_step_limit=" + replay.actorStepLimit );
			out.newLine();
out.write( "termination=" + replay.termination );
  			out.newLine();
out.write( "reward_profile=" + replay.rewardProfile.key() );
  			out.newLine();
  			out.write( "group=" + ( replay.group == null ? "" : replay.group ));
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

				out.write( ' ' );
				out.write( step.quickslots );

				out.write( ' ' );
				out.write( Integer.toString( step.heroHp ));
				out.write( ' ' );
				out.write( Float.toString( step.turn ));
				out.write( ' ' );
				out.write( step.inventory );

				out.write( ' ' );
				out.write( Long.toString( step.baseDraws ));

				out.write( ' ' );
				out.write( Long.toString( step.baseFingerprint ));
				out.write( ' ' );
				out.write( Integer.toString( step.accepted ));
				out.newLine();
			}
		}
	}

	public static Replay read( File file ) throws IOException {

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

			if (parts.length > 5) step.quickslots = parts[ 5 ];
			if (parts.length > 6) step.heroHp = Integer.parseInt( parts[ 6 ] );
			if (parts.length > 7) step.turn = Float.parseFloat( parts[ 7 ] );
			if (parts.length > 8) step.inventory = parts[ 8 ];
			if (parts.length > 9) step.baseDraws = Long.parseLong( parts[ 9 ] );
			if (parts.length > 10) step.baseFingerprint = Long.parseLong( parts[ 10 ] );
			if (parts.length > 11) step.accepted = Integer.parseInt( parts[ 11 ] );
			replay.steps.add( step );
		}

		return replay;
	}


	public static Replay readHeader( File file ) throws IOException {
		java.util.List<String> lines = Files.readAllLines( file.toPath(), StandardCharsets.UTF_8 );
		Replay replay = parseHeader( lines, file.getPath() );

		int available = Math.max( 0, lines.size() - headerEnd( lines ) );
		replay.truncatedAt = available < replay.declaredSteps ? available : -1;
		replay.steps.clear();
		replay.quickslotsRecorded = bodyCarriesBindings( lines, headerEnd( lines ) );
		replay.stepFieldsDropped = bodyDropsStepFields( lines, headerEnd( lines ) );

		return replay;
	}


	private static boolean bodyCarriesBindings( java.util.List<String> lines, int from ){
		for (int i = from; i < lines.size(); i++){
			String[] parts = lines.get( i ).split( " " );
			if (parts.length < 6) continue;
			if (!parts[ 2 ].equals( "SLOT" ) && !parts[ 2 ].equals( "INVENTORY" )) continue;
			if (!parts[ 5 ].isEmpty()) return true;
		}
		return false;
	}


	private static boolean bodyDropsStepFields( java.util.List<String> lines, int from ){
		boolean sawStep = false;
		for (int i = from; i < lines.size(); i++){
			String[] parts = lines.get( i ).split( " " );
			if (parts.length < 8) continue;
			sawStep = true;
			String inventory = parts.length > 8 ? parts[ 8 ] : "";
			if (!parts[ 6 ].equals( "-1" ) || !parts[ 7 ].equals( "-1.0" ) || !inventory.isEmpty()){
				return false;
			}
		}
		return sawStep;
	}


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

				case "max_slots": replay.maxSlots = Integer.parseInt( value ); break;
				case "allow_equipping": replay.allowEquipping = Boolean.parseBoolean( value ); break;
				case "turn_limit_total": replay.turnLimitTotal = Integer.parseInt( value ); break;
				case "stall_limit": replay.stallLimit = Integer.parseInt( value ); break;
				case "free_step_budget": replay.freeStepBudget = Integer.parseInt( value ); break;
				case "actor_step_limit": replay.actorStepLimit = Integer.parseInt( value ); break;

				case "termination": replay.termination = value; break;
				case "reward_profile":
					replay.rewardProfile =
							com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
									.parse( value );
					break;
				case "steps":      replay.declaredSteps = Integer.parseInt( value ); break;

				case "group":      replay.group = value; break;
				default: break;
			}
		}

		return replay;
	}


	public static EnvConfig configFor( Replay replay ){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = replay.turnLimitPerFloor;
		config.maxSlots = replay.maxSlots;
		config.allowEquipping = replay.allowEquipping;
		config.turnLimitTotal = replay.turnLimitTotal;
		config.stallLimit = replay.stallLimit;
		config.freeStepBudget = replay.freeStepBudget;
		config.actorStepLimit = replay.actorStepLimit;
		config.rewardProfile = replay.rewardProfile;
		return config;
	}


	public static class Verification {
		public double score;
		public int depth;
		public int turns;
		public int stepsVerified;
		public boolean diverged;
		public int divergedAt = -1;


		public String divergence = "";


		public int unwatchableAt = -1;


		public String unwatchable = "";


		public boolean passed(){
			return !diverged && unwatchableAt < 0;
		}
	}


	public static Verification verify( Replay replay, SPDEnv env ){
		return verify( replay, env, null );
	}


	public interface StepObserver {

		default void onReset(){}

		void onStep( int index, Replay.Step step, boolean running );
	}


	public static Verification verify( Replay replay, SPDEnv env, StepObserver observer ){
		Verification result = new Verification();

		HeroClass heroClass;
		try {
			heroClass = HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			heroClass = HeroClass.WARRIOR;
		}

		EnvConfig replayed = configFor( replay );
		env.config().turnLimitPerFloor = replayed.turnLimitPerFloor;
		env.config().maxSlots = replayed.maxSlots;
		env.config().allowEquipping = replayed.allowEquipping;
		env.config().turnLimitTotal = replayed.turnLimitTotal;
		env.config().stallLimit = replayed.stallLimit;
		env.config().freeStepBudget = replayed.freeStepBudget;
		env.config().actorStepLimit = replayed.actorStepLimit;
		env.reset( replay.seedText, heroClass );
		if (observer != null) observer.onReset();

		boolean traceStream = replay.recordsRngFingerprint();
		if (traceStream ){
			com.watabou.utils.RandomTrace.reset();
			com.watabou.utils.RandomTrace.enable();
		} else {
			com.watabou.utils.RandomTrace.disable();
		}

		boolean wireDroppedFields = replay.wireDroppedStepFields();

		for (int i = 0; i < replay.steps.size(); i++){
			if (!env.running()) break;

			Replay.Step step = replay.steps.get( i );
			Action action = Action.valueOf( step.action );

			String unwatchable = Quickslots.prepare( step, i, wireDroppedFields );
			if (unwatchable != null){
				result.unwatchableAt = i;
				result.unwatchable = unwatchable;
				break;
			}

			env.step( action, step.slot );
			result.stepsVerified++;

boolean heroAlive = Dungeon.hero.isAlive();
		String rng = heroAlive ? StepRngAssertion.checkRngParity( i, step.baseDraws,
					com.watabou.utils.RandomTrace.baseDraws() ) : null;
		if (rng == null && heroAlive ){
			rng = StepRngAssertion.checkFingerprintParity( i, step.baseDraws,
					step.baseFingerprint, com.watabou.utils.RandomTrace.baseFingerprint() );
		}
			if (rng != null ){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = rng;
				break;
			}

			if (step.accepted >= 0 && (step.accepted == 1) != env.lastStepActed() ){
				result.diverged = true;
				result.divergedAt = i;
				result.divergence = "action " + step.action + "/" + step.slot + " in " + step.mode
						+ ": the recording says the engine " + (step.accepted == 1 ? "accepted" : "refused")
						+ " it and the replay " + (env.lastStepActed() ? "accepted" : "refused")
						+ " it. The two sides are playing different actions from here, and every later"
						+ " position, health and turn comparison is downstream of that.";
				break;
			}

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

			if (observer != null) observer.onStep( i, step, true );
		}

		if (!result.diverged && result.unwatchableAt < 0 && result.stepsVerified < replay.steps.size()){
			result.diverged = true;
			result.divergedAt = result.stepsVerified;
			result.divergence = "the replay ended (" + env.endReason() + ") after " + result.stepsVerified
					+ " of " + replay.steps.size() + " recorded steps";
		}

		result.score = env.ledger().total();
		result.depth = env.depth();
		result.turns = env.turnsTotal();
		return result;
	}
}
