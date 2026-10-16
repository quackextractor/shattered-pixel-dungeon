package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.PpoHyperparameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Loads environment and learning settings from a file and the environment, instead of from Java
 * literals.
 *
 * <p><b>Why.</b> A hyperparameter sweep that has to edit source and recompile to try a value is a sweep
 * nobody runs often enough. Worse, the values were duplicated: {@code learningRate} was a field on both
 * {@code Network} and {@code PPO}, {@code epochs} and {@code minibatchSize} appeared on {@code PPO} and
 * again on {@code TrainOptions}, and the stall timeout was a literal in {@code WorkerPool} whose
 * {@code TrainOptions} default could disagree with it. {@link PpoHyperparameters} now holds one copy of
 * each; this class is what puts external values into it and into {@link EnvConfig}.
 *
 * <p><b>Resolution order, lowest priority first.</b>
 * <ol>
 *   <li>the compiled defaults on {@link EnvConfig} and {@link PpoHyperparameters}</li>
 *   <li>the properties file, if one is given</li>
 *   <li>environment variables named {@code SPD_} plus the key in upper case with {@code .} replaced by
 *       {@code _}, so {@code rl.learning_rate} is {@code SPD_RL_LEARNING_RATE}</li>
 * </ol>
 *
 * <p>The environment layer is what makes this usable from a container without baking a file into the
 * image, and a {@code .env} loaded by the shell is indistinguishable from it - no parser, no dependency.
 *
 * <p><b>Command-line flags still win over all of it.</b> A person who typed {@code --max-turns 400} meant
 * 400 turns, and a config file that could silently override an explicit flag would be a trap. That is
 * why the file is applied first and the flags afterwards, in the caller's own order.
 *
 * <p><b>Properties, not YAML.</b> {@code java.util.Properties} is in the JDK, so this adds no dependency
 * to a module whose whole point is running headlessly with a minimal classpath. The cost is no nested
 * structure, which for a flat set of scalars is no cost at all; keys are dotted names
 * ({@code rl.learning_rate}), not a tree.
 *
 * <p><b>Unknown keys warn, bad values fail.</b> A misspelled key must not be silent - a run tuned by
 * someone who believed a setting applied is the failure this most needs to prevent - so an unrecognised
 * key names itself and the file it came from. A malformed <em>value</em> is different: defaulting it would
 * hand back a configuration nobody asked for, in a system whose whole value is that its settings are
 * what it says they are. So it throws, naming the key, the file and the text that could not be read.
 */
public class EnvConfigBinder {

	/**
	 * Every key this binder understands, in the order they appear in the shipped properties file.
	 *
	 * <p>Exposed so {@code diag.ConfigCheck} can assert that the shipped file and this class agree in
	 * both directions: a key in the file the binder ignores would tune nothing, and a key the binder
	 * accepts that the file omits would be a setting with no documented default.
	 */
	public static final List<String> KEYS = List.of(

			"env.max_slots",
			"env.grid_width",
			"env.grid_height",
			"env.turn_limit_per_floor",
			"env.turn_limit_total",
			"env.actor_step_limit",
			"env.stall_limit",
	"env.free_step_budget",
			"env.max_view_distance",
			"env.honour_vision_buffs",
			"env.mimics_visible_out_of_fog",
			"env.allow_equipping",
			"env.allow_trading",
			"env.allow_alchemy",
			"env.persist_saves",
			"env.dialog_age_seconds",

			"reward.profile",
			"reward.turn_cost",
			"reward.depth_reward",
			"reward.explore_reward",
			"reward.max_hp_reward",
			"reward.talent_reward",
			"reward.gold_reward",
			"reward.damage_penalty",
			"reward.kill_reward",
			"reward.sneak_attack_reward",
			"reward.free_step_cost_factor",
			"reward.death_penalty",
			"reward.stall_penalty",
			"reward.turn_limit_penalty",
			"reward.victory_reward",
			"reward.curriculum_progress",

			"rl.learning_rate",
			"rl.gamma",
			"rl.gae_lambda",
			"rl.clip_epsilon",
			"rl.entropy_coefficient",
			"rl.epochs",
			"rl.minibatch_size",
			"rl.update_threads",
			"rl.sample_rate",
			"rl.max_sampled_per_episode",
			"rl.max_samples_per_generation",
			"rl.stall_seconds",

			"rl.chunk_length",
			"rl.tail_steps",
			"rl.normalise_values",
			"rl.value_norm_momentum",
			"rl.value_loss_coefficient",
			"rl.value_gradient_clip",
			"rl.huber_values",
			"rl.max_grad_norm",
			"rl.adam_beta1",
			"rl.adam_beta2",
			"rl.adam_eps",

			"train.random_seed_rate",
			"train.checkpoint_every",
			"train.record_every",

			"ipc.pipe_buffer_bytes",
			"ipc.push_chunk_bytes",

			"worker.heap_min",
			"worker.heap_max",
			"worker.gc_pause_ms"
	);

	/** Prefix for the environment-variable form of every key. */
	public static final String ENV_PREFIX = "SPD_";

	private final List<String> warnings = new ArrayList<>();

	/** Warnings from the last load, for a caller to report. Never null. */
	public List<String> warnings(){
		return warnings;
	}

	/** What one load produced. */
	public static class Loaded {
		public final EnvConfig config;
		public final PpoHyperparameters hyper;
		public final int keysApplied;

		/**
		 * Warnings from this load, so a caller can report them without parsing stderr.
		 *
		 * <p>Collected as well as printed because a warning nobody can read programmatically is a warning
		 * a test cannot assert on - and {@code diag.ConfigCheck} needs to assert that an unknown key
		 * produces one, which is the only observable difference between "warns and continues" and
		 * "silently ignores".
		 */
		public final List<String> warnings;

		Loaded( EnvConfig config, PpoHyperparameters hyper, int keysApplied, List<String> warnings ){
			this.config = config;
			this.hyper = hyper;
			this.keysApplied = keysApplied;
			this.warnings = List.copyOf( warnings );
		}
	}

	private EnvConfigBinder() {}

	/**
	 * Loads a configuration from the compiled defaults alone.
	 *
	 * <p>Separate from {@link #load} so a caller that has no file does not have to invent a path, and so
	 * the "no file" case is a supported call rather than a null check at every call site.
	 */
	public static Loaded defaults(){
		return new Loaded( new EnvConfig(), new PpoHyperparameters(), 0, List.of() );
	}

	/**
	 * Loads from a properties file, then from the environment.
	 *
	 * @param file the file to read, or null for the compiled defaults alone
	 * @throws IllegalArgumentException if a value cannot be read as the type its key requires, or if the
	 *         merged learning settings fail {@link PpoHyperparameters#validate()}
	 * @throws IOException if the file exists but cannot be read
	 */
	public static Loaded load( Path file ) throws IOException {
		EnvConfigBinder binder = new EnvConfigBinder();

		EnvConfig config = new EnvConfig();
		PpoHyperparameters hyper = new PpoHyperparameters();

		Properties props = new Properties();

		if (file != null){
			if (!Files.isReadable( file )){

				System.err.println( "[WARN] no configuration at " + file
						+ "; using the compiled defaults." );
			} else {
				try (InputStream in = Files.newInputStream( file )){
					props.load( in );
				}
			}
		}

		Path dotEnv = dotEnvBeside( file );
		Properties fromDotEnv = dotEnv == null ? new Properties() : dotEnv( dotEnv, binder );


		Set<String> known = new LinkedHashSet<>( KEYS );

		for (String name : props.stringPropertyNames()){
			if (!known.contains( name )){
				binder.warn( "unknown key '" + name + "' in " + file
						+ " - it tunes nothing. Known keys are listed in"
						+ " superintelligence/superintelligence.properties." );
			}
		}

		int applied = 0;

		for (String key : KEYS){
			String envName = environmentName( key );
			String fromEnv = System.getenv( envName );
			String fromDotEnvFile = fromDotEnv.getProperty( envName );
			String raw = props.getProperty( key );

			boolean fromFile = raw != null;
			String value = (fromEnv != null) ? fromEnv
					: (fromDotEnvFile != null ? fromDotEnvFile : raw );
			if (value == null) continue;

			String origin = fromEnv != null ? ("environment variable " + envName)
					: ("key " + key + " in "
							+ (fromDotEnv.containsKey( key ) ? dotEnv : file ));
			binder.apply( config, hyper, key, value, origin );
			applied++;
			if (!fromFile && (fromEnv != null || fromDotEnvFile != null)){

				binder.warn( key + " came from " + envName
						+ " rather than a file, so it is not part of the recorded configuration." );
			}
		}

		hyper.validate();

		return new Loaded( config, hyper, applied, binder.warnings );
	}

	/**
	 * The {@code .env} to read, or null when there is none.
	 *
	 * <p>Beside the properties file when one was given, and beside the working directory otherwise - which
	 * is where {@code .env.example} sits, so a run configured by copying it next to the module finds it
	 * without being told where it is.
	 */
	/**
	 * The environment-variable name for a key: {@code rl.learning_rate} becomes
	 * {@code SPD_RL_LEARNING_RATE}.
	 */
	public static String environmentName( String key ){
		return ENV_PREFIX + key.toUpperCase().replace( '.', '_' ).replace( '-', '_' );
	}

	private void apply( EnvConfig config, PpoHyperparameters hyper,
			String key, String value, String origin ){

		switch (key) {

			case "env.max_slots":            config.maxSlots = integer( key, value, origin ); break;
			case "env.grid_width":           config.gridWidth = integer( key, value, origin ); break;
			case "env.grid_height":          config.gridHeight = integer( key, value, origin ); break;
			case "env.turn_limit_per_floor": config.turnLimitPerFloor = integer( key, value, origin ); break;
			case "env.turn_limit_total":     config.turnLimitTotal = integer( key, value, origin ); break;
			case "env.actor_step_limit":     config.actorStepLimit = integer( key, value, origin ); break;
			case "env.stall_limit":          config.stallLimit = integer( key, value, origin ); break;
			case "env.free_step_budget":     config.freeStepBudget = integer( key, value, origin ); break;
			case "env.max_view_distance":    config.maxViewDistance = integer( key, value, origin ); break;
			case "env.honour_vision_buffs":  config.honourVisionBuffs = bool( key, value, origin ); break;
			case "env.mimics_visible_out_of_fog":
				config.mimicsVisibleOutOfFog = bool( key, value, origin ); break;
			case "env.allow_equipping":      config.allowEquipping = bool( key, value, origin ); break;
			case "env.allow_trading":        config.allowTrading = bool( key, value, origin ); break;
			case "env.allow_alchemy":        config.allowAlchemy = bool( key, value, origin ); break;
			case "env.persist_saves":        config.persistSaves = bool( key, value, origin ); break;
			case "env.dialog_age_seconds":   config.dialogAgeSeconds = decimal( key, value, origin ); break;

			case "reward.profile":
				try {
					config.rewardProfile =
							com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
									.parse( value );
				} catch (IllegalArgumentException e){
					throw new IllegalArgumentException( "key " + key + " in " + origin + ": "
							+ e.getMessage() );
				}
				break;
			case "reward.turn_cost":         config.turnCost = decimal( key, value, origin ); break;
			case "reward.depth_reward":      config.depthReward = decimal( key, value, origin ); break;
			case "reward.explore_reward":    config.exploreReward = decimal( key, value, origin ); break;
			case "reward.max_hp_reward":     config.maxHpReward = decimal( key, value, origin ); break;
			case "reward.talent_reward":     config.talentReward = decimal( key, value, origin ); break;
			case "reward.gold_reward":       config.goldReward = decimal( key, value, origin ); break;
			case "reward.damage_penalty":    config.damagePenalty = decimal( key, value, origin ); break;
			case "reward.kill_reward":       config.killReward = decimal( key, value, origin ); break;
			case "reward.sneak_attack_reward":
				config.sneakAttackReward = decimal( key, value, origin ); break;
			case "reward.free_step_cost_factor":
				config.freeStepCostFactor = decimal( key, value, origin ); break;
			case "reward.death_penalty":     config.deathPenalty = decimal( key, value, origin ); break;
			case "reward.stall_penalty":     config.stallPenalty = decimal( key, value, origin ); break;
			case "reward.turn_limit_penalty":
				config.turnLimitPenalty = decimal( key, value, origin ); break;
			case "reward.victory_reward":    config.victoryReward = decimal( key, value, origin ); break;
			case "reward.curriculum_progress":
				config.curriculumProgress = decimal( key, value, origin ); break;

			case "rl.learning_rate":     hyper.learningRate = decimal( key, value, origin ); break;
			case "rl.gamma":             hyper.gamma = decimal( key, value, origin ); break;
			case "rl.gae_lambda":        hyper.lambda = decimal( key, value, origin ); break;
			case "rl.clip_epsilon":      hyper.clipEpsilon = decimal( key, value, origin ); break;
			case "rl.entropy_coefficient": hyper.entropyCoeff = decimal( key, value, origin ); break;
			case "rl.epochs":            hyper.epochs = integer( key, value, origin ); break;
			case "rl.minibatch_size":    hyper.minibatchSize = integer( key, value, origin ); break;
			case "rl.update_threads":    hyper.updateThreads = integer( key, value, origin ); break;
			case "rl.sample_rate":       hyper.sampleRate = decimal( key, value, origin ); break;
			case "rl.max_sampled_per_episode":
				hyper.maxSampledPerEpisode = integer( key, value, origin ); break;
			case "rl.max_samples_per_generation":
				hyper.maxSamplesPerGeneration = integer( key, value, origin ); break;
			case "rl.stall_seconds":     hyper.stallSeconds = integer( key, value, origin ); break;

			case "rl.chunk_length":           hyper.chunkLength = integer( key, value, origin ); break;
			case "rl.tail_steps":             hyper.tailSteps = integer( key, value, origin ); break;
			case "rl.normalise_values":       hyper.normaliseValues = bool( key, value, origin ); break;
			case "rl.value_norm_momentum":    hyper.valueNormMomentum = decimal( key, value, origin ); break;
			case "rl.value_loss_coefficient": hyper.valueLossCoefficient = decimal( key, value, origin ); break;
			case "rl.value_gradient_clip":    hyper.valueGradientClip = decimal( key, value, origin ); break;
			case "rl.huber_values":           hyper.huberValues = bool( key, value, origin ); break;
			case "rl.max_grad_norm":          hyper.maxGradNorm = decimal( key, value, origin ); break;
			case "rl.adam_beta1":             hyper.adamBeta1 = decimal( key, value, origin ); break;
			case "rl.adam_beta2":             hyper.adamBeta2 = decimal( key, value, origin ); break;
			case "rl.adam_eps":               hyper.adamEps = decimal( key, value, origin ); break;

			case "train.random_seed_rate":    hyper.randomSeedRate = decimal( key, value, origin ); break;
			case "train.checkpoint_every":    hyper.checkpointEvery = integer( key, value, origin ); break;
			case "train.record_every":        hyper.recordEvery = integer( key, value, origin ); break;

			case "ipc.pipe_buffer_bytes":     hyper.pipeBufferBytes = integer( key, value, origin ); break;
			case "ipc.push_chunk_bytes":      hyper.pushChunkBytes = integer( key, value, origin ); break;

			case "worker.heap_min":           hyper.workerHeapMin = text( key, value, origin ); break;
			case "worker.heap_max":           hyper.workerHeapMax = text( key, value, origin ); break;
			case "worker.gc_pause_ms":        hyper.workerGcPauseMs = integer( key, value, origin ); break;

			default:

				throw new IllegalStateException( "no handler for key '" + key + "'" );
		}
	}

	private static Path dotEnvBeside( Path file ){
		Path dir = (file != null && file.getParent() != null) ? file.getParent() : Path.of( "." );
		Path candidate = dir.resolve( ".env" );
		return Files.isReadable( candidate ) ? candidate : null;
	}

	/**
	 * Parses {@code .env} as {@code KEY=VALUE} lines, keyed by the variable name as written.
	 *
	 * <p>Between the properties file and the process environment, which is where the documentation has
	 * always said it sits. It used to sit nowhere: the JVM does not read {@code .env}, so the file had to
	 * be piped into the shell by hand, and every developer who skipped that step ran with settings they
	 * did not believe they were using - silently, because nothing in the report said the file had been
	 * ignored.
	 *
	 * <p>Names are <em>not</em> converted back to dotted keys here. {@code SPD_MAX_SAMPLED_PER_EPISODE}
	 * and {@code SPD_MAX.SAMPLED.PER.EPISODE} are the same variable, and only one of them can be
	 * reconstructed from the other, so the caller matches against {@link #environmentName} instead.
	 */
	static Properties dotEnv( Path file, EnvConfigBinder binder ){
		Properties out = new Properties();

		List< String > lines;
		try {
			lines = Files.readAllLines( file );
		} catch (IOException e){
			binder.warn( "could not read " + file + ": " + e.getMessage() );
			return out;
		}

		for (String line : lines){
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith( "#" )) continue;

			int eq = trimmed.indexOf( '=' );
			if (eq <= 0){
				binder.warn( "ignored " + file + " line with no '=': " + trimmed );
				continue;
			}

			String name = trimmed.substring( 0, eq ).trim();
			String value = unquote( trimmed.substring( eq + 1 ).trim() );

			if (!name.toUpperCase().startsWith( ENV_PREFIX )){
				binder.warn( "ignored " + file + " entry " + name + " because it does not begin with "
						+ ENV_PREFIX );
				continue;
			}

			out.setProperty( name.toUpperCase(), value );
		}

		return out;
	}

	private static String unquote( String value ){
		if (value.length() >= 2
				&& (value.startsWith( "\"" ) && value.endsWith( "\"" ))
				|| (value.startsWith( "'" ) && value.endsWith( "'" ))){
			return value.substring( 1, value.length() - 1 );
		}
		return value;
	}	private static int integer( String key, String value, String origin ){
		try {
			return Integer.parseInt( value.trim() );
		} catch (NumberFormatException e){
			throw new IllegalArgumentException( badValue( key, value, origin, "an integer" ));
		}
	}

	private static float decimal( String key, String value, String origin ){
		try {
			return Float.parseFloat( value.trim() );
		} catch (NumberFormatException e){
			throw new IllegalArgumentException( badValue( key, value, origin, "a number" ));
		}
	}

	private static boolean bool( String key, String value, String origin ){
		String v = value.trim().toLowerCase();
		if (v.equals( "true" ) || v.equals( "yes" ) || v.equals( "1" )) return true;
		if (v.equals( "false" ) || v.equals( "no" ) || v.equals( "0" )) return false;
		throw new IllegalArgumentException( badValue( key, value, origin,
				"true or false (yes/no and 1/0 also accepted)" ));
	}

	/** A heap size, which is text rather than a number: {@code 1536m} is not an integer of megabytes. */
	private static String text( String key, String value, String origin ){
		String v = value.trim();
		if (v.isEmpty()){
			throw new IllegalArgumentException( badValue( key, value, origin, "a JVM heap size such as 1536m" ));
		}
		return v;
	}

	private static String badValue( String key, String value, String origin, String expected ){
		return key + " needs " + expected + ", but " + origin + " said \"" + value + "\"."
				+ " Nothing was defaulted: a configuration that is not the one you asked for is worse"
				+ " than one that refuses to load.";
	}

	private void warn( String message ){
		warnings.add( message );
		System.err.println( "[WARN] " + message );
	}

	/**
	 * Whether the binder is running against a file, for a caller that wants to log which one.
	 *
	 * <p>Static because the environment-variable layer makes the effective configuration a property of the
	 * process as much as of the file, and a caller asking "which file?" is really asking "how do I
	 * reproduce this run?".
	 */
	public static String describe( Path file, int keysApplied ){
		return "config file " + (file == null ? "(none, compiled defaults)"
				: (Files.isReadable( file ) ? file.toString() : file + " (unreadable)"))
				+ ", " + keysApplied + " key(s) applied, environment overrides "
				+ Arrays.toString( environmentKeysPresent() );
	}

	private static String[] environmentKeysPresent(){
		List<String> present = new ArrayList<>();
		for (String key : KEYS){
			String name = environmentName( key );
			if (System.getenv( name ) != null) present.add( name );
		}
		return present.toArray( new String[ 0 ] );
	}
}
