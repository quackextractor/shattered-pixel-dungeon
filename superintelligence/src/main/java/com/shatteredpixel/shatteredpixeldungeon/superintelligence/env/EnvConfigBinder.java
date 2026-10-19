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


public class EnvConfigBinder {


	public static final List<String> KEYS = List.of(

			"env.max_slots",
			"env.grid_width",
			"env.grid_height",
			"env.turn_limit_per_floor",
			"env.turn_limit_total",
			"env.actor_step_limit",
			"env.stall_limit",
	"env.free_step_budget",
			"env.allow_equipping",
			"env.allow_alchemy",
			"env.sub_mode_progress_only",
			"env.record_rng_fingerprint",
			"env.dialog_age_seconds",

			"reward.profile",
			"reward.turn_cost",
			"reward.depth_reward",
			"reward.explore_reward",
		 "reward.stair_potential_weight",
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

			"reward.identified_reward",
			"reward.curse_equipped_penalty",
			"reward.curse_removed_reward",
			"reward.equip_too_strong_penalty",
			"reward.cured_debuff_reward",
			"reward.eaten_early_penalty",
			"reward.menu_noop_penalty",
			"reward.level_up_reward",
			"reward.strength_gain_reward",
			"reward.gold_lost_factor",
			"reward.item_value_bag",
			"reward.item_value_equip_base",
			"reward.item_value_equip_level",
			"reward.item_value_other",
			"reward.boss_slain_factor",
			"reward.craft_reward",
			"reward.craft_meat_pie_reward",
			"reward.max_hp_gain_cap",
			"reward.curriculum_fade_start_depth",
			"reward.curriculum_fade_end_depth",

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


	public static final String ENV_PREFIX = "SPD_";

	private final List<String> warnings = new ArrayList<>();


	public List<String> warnings(){
		return warnings;
	}


	public static class Loaded {
		public final EnvConfig config;
		public final PpoHyperparameters hyper;
		public final int keysApplied;


		public final List<String> warnings;

		Loaded( EnvConfig config, PpoHyperparameters hyper, int keysApplied, List<String> warnings ){
			this.config = config;
			this.hyper = hyper;
			this.keysApplied = keysApplied;
			this.warnings = List.copyOf( warnings );
		}
	}

	private EnvConfigBinder() {}


	public static Loaded defaults(){
		return new Loaded( new EnvConfig(), new PpoHyperparameters(), 0, List.of() );
	}


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

		Set<String> knownEnv = new LinkedHashSet<>();
		for (String key : KEYS) knownEnv.add( environmentName( key ));
		for (String name : fromDotEnv.stringPropertyNames()){
			if (!knownEnv.contains( name )){
				binder.warn( "unknown variable '" + name + "' in " + dotEnv + " - it tunes nothing." );
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
					: fromDotEnvFile != null ? ("key " + envName + " in " + dotEnv)
					: ("key " + key + " in " + file);
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
			case "env.allow_equipping":      config.allowEquipping = bool( key, value, origin ); break;
			case "env.allow_alchemy":        config.allowAlchemy = bool( key, value, origin ); break;
			case "env.sub_mode_progress_only":
				config.subModeProgressOnly = bool( key, value, origin ); break;
			case "env.record_rng_fingerprint":
				config.recordRngFingerprint = bool( key, value, origin ); break;
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
		 case "reward.stair_potential_weight":
		 	config.stairPotentialWeight = decimal( key, value, origin ); break;
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
			case "reward.identified_reward":
				config.identifiedReward = decimal( key, value, origin ); break;
			case "reward.curse_equipped_penalty":
				config.curseEquippedPenalty = decimal( key, value, origin ); break;
			case "reward.curse_removed_reward":
				config.curseRemovedReward = decimal( key, value, origin ); break;
			case "reward.equip_too_strong_penalty":
				config.equipTooStrongPenalty = decimal( key, value, origin ); break;
			case "reward.cured_debuff_reward":
				config.curedDebuffReward = decimal( key, value, origin ); break;
			case "reward.eaten_early_penalty":
				config.eatenEarlyPenalty = decimal( key, value, origin ); break;
			case "reward.menu_noop_penalty":
				config.menuNoopPenalty = decimal( key, value, origin ); break;
			case "reward.level_up_reward":
				config.levelUpReward = decimal( key, value, origin ); break;
			case "reward.strength_gain_reward":
				config.strengthGainReward = decimal( key, value, origin ); break;
			case "reward.gold_lost_factor":
				config.goldLostFactor = decimal( key, value, origin ); break;
			case "reward.item_value_bag":
				config.itemValueBag = decimal( key, value, origin ); break;
			case "reward.item_value_equip_base":
				config.itemValueEquipBase = decimal( key, value, origin ); break;
			case "reward.item_value_equip_level":
				config.itemValueEquipLevel = decimal( key, value, origin ); break;
			case "reward.item_value_other":
				config.itemValueOther = decimal( key, value, origin ); break;
			case "reward.boss_slain_factor":
				config.bossSlainFactor = decimal( key, value, origin ); break;
			case "reward.craft_reward":
				config.craftReward = decimal( key, value, origin ); break;
			case "reward.craft_meat_pie_reward":
				config.craftMeatPieReward = decimal( key, value, origin ); break;
			case "reward.max_hp_gain_cap":
				config.maxHpGainCap = decimal( key, value, origin ); break;
			case "reward.curriculum_fade_start_depth":
				config.curriculumFadeStartDepth = integer( key, value, origin ); break;
			case "reward.curriculum_fade_end_depth":
				config.curriculumFadeEndDepth = integer( key, value, origin ); break;

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
				&& ((value.startsWith( "\"" ) && value.endsWith( "\"" ))
				|| (value.startsWith( "'" ) && value.endsWith( "'" )))){
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
