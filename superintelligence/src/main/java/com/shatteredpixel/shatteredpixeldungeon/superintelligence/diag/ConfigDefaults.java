package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.PpoHyperparameters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class ConfigDefaults {

	static final List<String> failures = new ArrayList<>();

	static File scratch;

	static boolean verbose;

	private ConfigDefaults(){}

	static String compiledValue( String key, EnvConfig config, PpoHyperparameters hyper ){
		switch (key ) {
			case "env.max_slots":             return String.valueOf( config.maxSlots );
			case "env.grid_width":            return String.valueOf( config.gridWidth );
			case "env.grid_height":           return String.valueOf( config.gridHeight );
			case "env.turn_limit_per_floor":  return String.valueOf( config.turnLimitPerFloor );
			case "env.turn_limit_total":      return String.valueOf( config.turnLimitTotal );
			case "env.actor_step_limit":      return String.valueOf( config.actorStepLimit );
			case "env.stall_limit":           return String.valueOf( config.stallLimit );
			case "env.free_step_budget":      return String.valueOf( config.freeStepBudget );
			case "env.max_view_distance":     return String.valueOf( config.maxViewDistance );
			case "env.dialog_age_seconds":    return String.valueOf( config.dialogAgeSeconds );
			case "env.honour_vision_buffs":   return String.valueOf( config.honourVisionBuffs );
			case "env.mimics_visible_out_of_fog":
				return String.valueOf( config.mimicsVisibleOutOfFog );
			case "env.allow_equipping":       return String.valueOf( config.allowEquipping );
			case "env.allow_trading":         return String.valueOf( config.allowTrading );
			case "env.allow_alchemy":         return String.valueOf( config.allowAlchemy );
			case "env.sub_mode_progress_only":   return String.valueOf( config.subModeProgressOnly );
			case "env.persist_saves":         return String.valueOf( config.persistSaves );

			case "reward.profile":            return config.rewardProfile.key();
			case "reward.turn_cost":          return String.valueOf( config.turnCost );
			case "reward.depth_reward":       return String.valueOf( config.depthReward );
			case "reward.explore_reward":     return String.valueOf( config.exploreReward );
			case "reward.max_hp_reward":      return String.valueOf( config.maxHpReward );
			case "reward.talent_reward":      return String.valueOf( config.talentReward );
			case "reward.gold_reward":        return String.valueOf( config.goldReward );
			case "reward.damage_penalty":     return String.valueOf( config.damagePenalty );
			case "reward.kill_reward":        return String.valueOf( config.killReward );
			case "reward.sneak_attack_reward":
				return String.valueOf( config.sneakAttackReward );
			case "reward.free_step_cost_factor":
				return String.valueOf( config.freeStepCostFactor );
			case "reward.death_penalty":      return String.valueOf( config.deathPenalty );
			case "reward.stall_penalty":      return String.valueOf( config.stallPenalty );
			case "reward.turn_limit_penalty": return String.valueOf( config.turnLimitPenalty );
			case "reward.victory_reward":     return String.valueOf( config.victoryReward );
			case "reward.curriculum_progress": return String.valueOf( config.curriculumProgress );
			case "reward.stair_potential_weight":
				return String.valueOf( config.stairPotentialWeight );
			case "reward.identified_reward":
				return String.valueOf( config.identifiedReward );
			case "reward.curse_equipped_penalty":
				return String.valueOf( config.curseEquippedPenalty );
			case "reward.curse_removed_reward":
				return String.valueOf( config.curseRemovedReward );
			case "reward.equip_too_strong_penalty":
				return String.valueOf( config.equipTooStrongPenalty );
			case "reward.cured_debuff_reward":
				return String.valueOf( config.curedDebuffReward );
			case "reward.eaten_early_penalty":
				return String.valueOf( config.eatenEarlyPenalty );
			case "reward.menu_noop_penalty":
				return String.valueOf( config.menuNoopPenalty );
			case "reward.level_up_reward":
				return String.valueOf( config.levelUpReward );
			case "reward.strength_gain_reward":
				return String.valueOf( config.strengthGainReward );
			case "reward.gold_lost_factor":
				return String.valueOf( config.goldLostFactor );
			case "reward.item_value_bag":
				return String.valueOf( config.itemValueBag );
			case "reward.item_value_equip_base":
				return String.valueOf( config.itemValueEquipBase );
			case "reward.item_value_equip_level":
				return String.valueOf( config.itemValueEquipLevel );
			case "reward.item_value_other":
				return String.valueOf( config.itemValueOther );
			case "reward.boss_slain_factor":
				return String.valueOf( config.bossSlainFactor );
			case "reward.max_hp_gain_cap":
				return String.valueOf( config.maxHpGainCap );
			case "reward.curriculum_fade_start_depth":
				return String.valueOf( config.curriculumFadeStartDepth );
			case "reward.curriculum_fade_end_depth":
				return String.valueOf( config.curriculumFadeEndDepth );

			case "rl.learning_rate":          return String.valueOf( hyper.learningRate );
			case "rl.gamma":                  return String.valueOf( hyper.gamma );
			case "rl.gae_lambda":             return String.valueOf( hyper.lambda );
			case "rl.clip_epsilon":           return String.valueOf( hyper.clipEpsilon );
			case "rl.entropy_coefficient":    return String.valueOf( hyper.entropyCoeff );
			case "rl.epochs":                 return String.valueOf( hyper.epochs );
			case "rl.minibatch_size":         return String.valueOf( hyper.minibatchSize );
			case "rl.update_threads":         return String.valueOf( hyper.updateThreads );
			case "rl.sample_rate":            return String.valueOf( hyper.sampleRate );
			case "rl.max_sampled_per_episode": return String.valueOf( hyper.maxSampledPerEpisode );
			case "rl.max_samples_per_generation":
				return String.valueOf( hyper.maxSamplesPerGeneration );
			case "rl.stall_seconds":          return String.valueOf( hyper.stallSeconds );
			case "rl.chunk_length":           return String.valueOf( hyper.chunkLength );
			case "rl.tail_steps":             return String.valueOf( hyper.tailSteps );
			case "rl.normalise_values":       return String.valueOf( hyper.normaliseValues );
			case "rl.value_norm_momentum":    return String.valueOf( hyper.valueNormMomentum );
			case "rl.value_loss_coefficient":  return String.valueOf( hyper.valueLossCoefficient );
			case "rl.value_gradient_clip":    return String.valueOf( hyper.valueGradientClip );
			case "rl.huber_values":           return String.valueOf( hyper.huberValues );
			case "rl.max_grad_norm":          return String.valueOf( hyper.maxGradNorm );
			case "rl.adam_beta1":             return String.valueOf( hyper.adamBeta1 );
			case "rl.adam_beta2":             return String.valueOf( hyper.adamBeta2 );
			case "rl.adam_eps":               return String.valueOf( hyper.adamEps );

			case "train.checkpoint_every":    return String.valueOf( hyper.checkpointEvery );
			case "train.record_every":        return String.valueOf( hyper.recordEvery );
			case "train.random_seed_rate":    return String.valueOf( hyper.randomSeedRate );

			case "ipc.pipe_buffer_bytes":     return String.valueOf( hyper.pipeBufferBytes );
			case "ipc.push_chunk_bytes":      return String.valueOf( hyper.pushChunkBytes );
			case "trainer.push_retries":      return String.valueOf( hyper.pushRetries );
			case "worker.heap_min":           return hyper.workerHeapMin;
			case "worker.heap_max":           return hyper.workerHeapMax;
			case "worker.gc_pause_ms":        return String.valueOf( hyper.workerGcPauseMs );

			default: return null;
		}
	}

	static String sentinelFor( String key, String current ){
		if (isBooleanKey( key )){
			return current.equals( "true" ) ? "false" : "true";
		}
		if (isTextKey( key )){
			return current.endsWith( "m" ) ? "512m" : "1024m";
		}
		return numericSentinelFor( key );
	}

	private static boolean isBooleanKey( String key ){
		switch ( key ) {
			case "env.honour_vision_buffs":
			case "env.mimics_visible_out_of_fog":
			case "env.allow_equipping":
			case "env.allow_trading":
			case "env.allow_alchemy":
			case "env.sub_mode_progress_only":
			case "env.persist_saves":
			case "rl.normalise_values":
			case "rl.huber_values":
				return true;
			default:
				return false;
		}
	}

	private static boolean isTextKey( String key ){
		return key.equals( "worker.heap_min" ) || key.equals( "worker.heap_max" );
	}

	private static String numericSentinelFor( String key ){
		switch ( key ) {
			case "env.max_slots":             return "9";
			case "env.grid_width":            return "24";
			case "env.grid_height":           return "24";
			case "env.turn_limit_per_floor":  return "777";
			case "env.turn_limit_total":      return "31337";
			case "env.actor_step_limit":      return "555";
			case "env.stall_limit":           return "42";
			case "env.free_step_budget":      return "77";
			case "env.max_view_distance":     return "7";
			case "env.dialog_age_seconds":    return "0.5";

			case "reward.profile":            return "depth_only";
			case "reward.turn_cost":          return "0.5";
			case "reward.depth_reward":       return "77.5";
			case "reward.explore_reward":     return "0.5";
			case "reward.max_hp_reward":      return "1.5";
			case "reward.talent_reward":      return "7.5";
			case "reward.gold_reward":        return "0.5";
			case "reward.damage_penalty":     return "0.5";
			case "reward.kill_reward":        return "1.5";
			case "reward.sneak_attack_reward": return "2.5";
			case "reward.free_step_cost_factor": return "0.9";
			case "reward.death_penalty":      return "250.0";
			case "reward.stall_penalty":      return "350.0";
			case "reward.turn_limit_penalty": return "75.0";
			case "reward.victory_reward":     return "2000.0";
			case "reward.curriculum_progress": return "0.5";
			case "reward.stair_potential_weight": return "0.5";
			case "reward.identified_reward": return "1.5";
			case "reward.curse_equipped_penalty": return "-1.5";
			case "reward.curse_removed_reward": return "3.5";
			case "reward.equip_too_strong_penalty": return "-4.5";
			case "reward.cured_debuff_reward": return "3.5";
			case "reward.eaten_early_penalty": return "-2.5";
			case "reward.menu_noop_penalty": return "-2.5";
			case "reward.level_up_reward": return "3.5";
			case "reward.strength_gain_reward": return "2.5";
			case "reward.gold_lost_factor": return "3.5";
			case "reward.item_value_bag": return "4.5";
			case "reward.item_value_equip_base": return "1.5";
			case "reward.item_value_equip_level": return "0.25";
			case "reward.item_value_other": return "0.75";
			case "reward.boss_slain_factor": return "3.5";
			case "reward.max_hp_gain_cap": return "7.0";
			case "reward.curriculum_fade_start_depth": return "4";
			case "reward.curriculum_fade_end_depth": return "9";

			case "rl.learning_rate":          return "0.007";
			case "rl.gamma":                  return "0.5";
			case "rl.gae_lambda":             return "0.5";
			case "rl.clip_epsilon":           return "0.1";
			case "rl.entropy_coefficient":    return "0.5";
			case "rl.sample_rate":            return "0.5";
			case "rl.epochs":                 return "3";
			case "rl.minibatch_size":         return "16";
			case "rl.update_threads":         return "2";
			case "rl.max_sampled_per_episode": return "128";
			case "rl.max_samples_per_generation": return "512";
			case "rl.stall_seconds":          return "60";
			case "rl.chunk_length":           return "4";
			case "rl.tail_steps":             return "7";
			case "rl.value_norm_momentum":    return "0.5";
			case "rl.value_loss_coefficient":  return "0.75";
			case "rl.value_gradient_clip":    return "3.5";
			case "rl.max_grad_norm":          return "0.75";
			case "rl.adam_beta1":             return "0.5";
			case "rl.adam_beta2":             return "0.5";
			case "rl.adam_eps":               return "0.001";

			case "train.checkpoint_every":    return "3";
			case "train.record_every":        return "3";
			case "train.random_seed_rate":    return "0.5";

			case "ipc.pipe_buffer_bytes":     return "65536";
			case "ipc.push_chunk_bytes":      return "8192";
			case "trainer.push_retries":      return "2";
			case "worker.gc_pause_ms":        return "50";

			default:
				throw new IllegalStateException( "no sentinel for key '" + key
						+ "'. Add one, or this key cannot be proven reachable." );
		}
	}

	static boolean sameValue( String a, String b ){
		if (a == null || b == null) return a == null && b == null;

		try {
			double x = Double.parseDouble( a.trim() );
			double y = Double.parseDouble( b.trim() );
			return Math.abs( x - y ) <= 1e-9 * Math.max( 1.0, Math.abs( y ) );
		} catch (NumberFormatException e){
			return a.trim().equals( b.trim() );
		}
	}

	static Path shippedFile(){
		Path direct = new File( "superintelligence/superintelligence.properties" ).toPath();
		if (Files.isReadable( direct )) return direct;

		Path fromRoot = new File( "superintelligence.properties" ).toPath();
		if (Files.isReadable( fromRoot )) return fromRoot;

		throw new IllegalStateException( "cannot find superintelligence.properties from "
				+ new File( "." ).getAbsolutePath()
				+ ". It is a committed file next to build.gradle; this check cannot pass without it." );
	}

	static Map<String, String> read( Path file ) throws IOException {
		Properties props = new Properties();
		try (java.io.InputStream in = Files.newInputStream( file )){
			props.load( in );
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : props.stringPropertyNames()){
			out.put( name, props.getProperty( name ));
		}
		return out;
	}

	static Path write( String name, String... lines ) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) sb.append( line ).append( '\n' );
		Path path = new File( scratch, name ).toPath();
		Files.write( path, sb.toString().getBytes( StandardCharsets.UTF_8 ) );
		return path;
	}

	static void fail( String message ){
		failures.add( message );
		if (verbose) System.out.println( "        [case] " + message );
	}
}