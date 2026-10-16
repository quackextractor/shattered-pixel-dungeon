/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.PpoHyperparameters;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

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

public class ConfigCheck {

	private static final int CHECKS = 10;

	private static final List<String> failures = new ArrayList<>();

	private static File scratch;

	private static boolean verbose = false;

	public static void main( String[] args ) throws IOException {
		if (args.length > 0 && (args[ 0 ].equals( "--verbose" ) || args[ 0 ].equals( "-v" ) )){
			verbose = true;
		}

		scratch = new File( System.getProperty( "java.io.tmpdir" ), "spd-configcheck" );

		scratch.mkdirs();

		checkTheBinderActuallyBinds();
		checkEveryConfigFieldTravelsToAWorker();
		checkTheShippedFileUsesOnlyKnownKeys();
		checkTheShippedFileMatchesTheCompiledDefaults();
		checkEveryKnownKeyIsReachable();
		checkUnknownKeysWarn();
		checkBadValuesAreRefused();
		checkOutputPathsFollowTheWorkingDirectory();
		checkDotEnvIsRead();
		checkDotEnvBeatsThePropertiesFile();

		if (failures.isEmpty()){
			System.out.println( "[OK]     configuration binding: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  configuration binding: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkTheBinderActuallyBinds() throws IOException {
		Path file = write( "positive.properties",
				"env.max_slots=7",
				"reward.depth_reward=99.5",
				"rl.learning_rate=0.5",
				"rl.gamma=0.5" );

		EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( file );

		if (loaded.config.maxSlots != 7){
			fail( "env.max_slots=7 in a file produced maxSlots=" + loaded.config.maxSlots
					+ ". The binder is not reading integer keys, so every default-comparison below would"
					+ " pass without the file having been read at all." );
			return;
		}
		if (loaded.config.depthReward != 99.5f){
			fail( "reward.depth_reward=99.5 produced " + loaded.config.depthReward );
			return;
		}
		if (loaded.hyper.learningRate != 0.5f){
			fail( "rl.learning_rate=0.5 produced " + loaded.hyper.learningRate
					+ ". The learning settings are not read from the same file as the environment ones." );
			return;
		}
		if (loaded.hyper.gamma != 0.5f){
			fail( "rl.gamma=0.5 produced " + loaded.hyper.gamma );
			return;
		}

		System.out.println( "  a value in a file changes the setting it names, for both halves of the"
				+ " configuration" );
	}

private static void checkEveryConfigFieldTravelsToAWorker() throws IOException {
	EnvConfig source = new EnvConfig();

	EnvConfig probe = new EnvConfig();
	probe.maxSlots = 3;             probe.gridWidth = 4;           probe.gridHeight = 5;
	probe.turnLimitPerFloor = 6;    probe.turnLimitTotal = 7;      probe.actorStepLimit = 8;
	probe.stallLimit = 9;           probe.freeStepBudget = 10;     probe.maxViewDistance = 11;
	probe.curriculumProgress = 0.25f;
	probe.turnCost = 1.5f;          probe.depthReward = 2.5f;      probe.exploreReward = 3.5f;
	probe.maxHpReward = 4.5f;       probe.talentReward = 5.5f;      probe.goldReward = 6.5f;
	probe.damagePenalty = 7.5f;     probe.killReward = 8.5f;        probe.sneakAttackReward = 9.5f;
	probe.freeStepCostFactor = 10.5f;
	probe.deathPenalty = 11.5f;     probe.stallPenalty = 12.5f;     probe.turnLimitPenalty = 13.5f;
	probe.victoryReward = 14.5f;
	probe.honourVisionBuffs = false;
	probe.mimicsVisibleOutOfFog = false;
	probe.allowEquipping = false;
	probe.allowTrading = false;
	probe.allowAlchemy = false;
	probe.persistSaves = true;
	probe.rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
			.parse( "depth_only" );

	java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
	probe.writeTo( new java.io.DataOutputStream( bytes ) );

	EnvConfig got = new EnvConfig();
	try {
		got.readFrom( new java.io.DataInputStream( new java.io.ByteArrayInputStream( bytes.toByteArray() )));
	} catch (java.io.EOFException e){

		fail( "writeTo wrote fewer values than readFrom reads, so a worker would desync mid-stream."
				+ " The two halves of the config wire format have drifted." );
		return;
	}

	EnvConfig copied = probe.copy();
	java.util.List< String > lost = new java.util.ArrayList<>();
	for (String field : ENV_FIELDS){
		Object want = fieldValue( copied, field );
		Object have = fieldValue( got, field );
		if (want == null || have == null){
			lost.add( field + " (no such field)" );
		} else if (!want.equals( have )){
			lost.add( field + " " + have + " != " + want );
		}
	}
	if (!lost.isEmpty()){
		fail( lost.size() + " of " + ENV_FIELDS.size() + " EnvConfig fields did not survive the trip to a"
				+ " worker: " + String.join( ", ", lost ));
		return;
	}

	java.io.ByteArrayOutputStream sized = new java.io.ByteArrayOutputStream();
	new EnvConfig().writeTo( new java.io.DataOutputStream( sized ));

	boolean readShort = false;
	try {
		EnvConfig partial = new EnvConfig();
		partial.readFrom( new java.io.DataInputStream(
				new java.io.ByteArrayInputStream( sized.toByteArray(), 0, sized.size() - 1 )));
	} catch (java.io.EOFException expected){
		readShort = true;
	}
	if (!readShort){
		fail( "readFrom consumed " + sized.size() + " bytes without wanting a "
				+ (sized.size() + 1) + "th, so writeTo and readFrom do not read the same number of"
				+ " values. A worker would desync mid-stream." );
	}
}

private static final java.util.List< String > ENV_FIELDS = java.util.Arrays.asList(
		"maxSlots", "gridWidth", "gridHeight", "turnLimitPerFloor", "turnLimitTotal", "actorStepLimit",
		"stallLimit", "freeStepBudget", "maxViewDistance", "curriculumProgress", "turnCost",
		"depthReward", "exploreReward", "maxHpReward", "talentReward", "goldReward", "damagePenalty",
		"killReward", "sneakAttackReward", "freeStepCostFactor", "deathPenalty", "stallPenalty",
		"turnLimitPenalty", "victoryReward", "honourVisionBuffs", "mimicsVisibleOutOfFog",
		"allowEquipping", "allowTrading", "allowAlchemy", "persistSaves", "rewardProfile" );

private static Object fieldValue( EnvConfig c, String name ){
	try {
		java.lang.reflect.Field f = EnvConfig.class.getField( name );
		return f.get( c );
	} catch (ReflectiveOperationException e){
		return null;
	}
}

private static void checkTheShippedFileUsesOnlyKnownKeys() throws IOException {
		Path shipped = shippedFile();
		Map<String, String> values = read( shipped );

		List<String> unknown = new ArrayList<>();
		for (String key : values.keySet()){
			if (!EnvConfigBinder.KEYS.contains( key )) unknown.add( key );
		}

		if (!unknown.isEmpty()){
			fail( shipped + " names " + unknown.size() + " key(s) the binder does not implement: "
					+ String.join( ", ", unknown ) + ". Each tunes nothing and would report no warning." );
			return;
		}

		System.out.println( "  all " + values.size() + " keys in " + shipped.getFileName()
				+ " are keys the binder implements" );
	}

	private static void checkTheShippedFileMatchesTheCompiledDefaults() throws IOException {
		Path shipped = shippedFile();
		Map<String, String> values = read( shipped );

		EnvConfigBinder.Loaded defaults = EnvConfigBinder.load( null );
		EnvConfig config = defaults.config;
		PpoHyperparameters hyper = defaults.hyper;

		List<String> mismatched = new ArrayList<>();

		for (Map.Entry<String, String> entry : values.entrySet()){
			String key = entry.getKey();
			String written = entry.getValue().trim();

			String actual = compiledValue( key, config, hyper );
			if (actual == null){

				continue;
			}
			if (!sameValue( actual, written )) mismatched.add( key + ": file says " + written
					+ ", code uses " + actual );
		}

		List<String> missing = new ArrayList<>();
		for (String key : EnvConfigBinder.KEYS){
			if (!values.containsKey( key )) missing.add( key );
		}

		if (!missing.isEmpty()){
			fail( shipped + " does not document " + missing.size() + " key(s) the binder accepts: "
					+ String.join( ", ", missing ) + ". A setting with no documented default cannot be"
					+ " tuned from the file, which is what the file is for." );
		}

		if (!mismatched.isEmpty()){
			fail( shipped + " disagrees with the compiled defaults in " + mismatched.size()
					+ " place(s): " + String.join( "; ", mismatched )
					+ ". One of the two is now wrong, and the file is the one a reader tunes from." );
		}

		if (missing.isEmpty() && mismatched.isEmpty()){
			System.out.println( "  all " + values.size() + " shipped defaults match the compiled ones, in"
					+ " both directions" );
		}
	}

	private static void checkEveryKnownKeyIsReachable() throws IOException {
		EnvConfigBinder.Loaded base = EnvConfigBinder.load( null );

		List<String> inert = new ArrayList<>();

		for (String key : EnvConfigBinder.KEYS){
			String current = compiledValue( key, base.config, base.hyper );
			if (current == null) continue;

			String sentinel = sentinelFor( key, current );
			Path file = write( "reachable.properties", key + "=" + sentinel );

			EnvConfigBinder.Loaded loaded;
			try {
				loaded = EnvConfigBinder.load( file );
			} catch (IllegalArgumentException e){
				inert.add( key + " (rejected: " + e.getMessage() + ")" );
				continue;
			}

			String after = compiledValue( key, loaded.config, loaded.hyper );

			if (after == null || sameValue( current, after )){
				inert.add( key + " (setting it to " + sentinel + " changed nothing: still " + after + ")" );
			}
		}

		if (!inert.isEmpty()){
			fail( inert.size() + " of " + EnvConfigBinder.KEYS.size() + " documented key(s) do nothing: "
					+ String.join( "; ", inert ) + ". Each is documented as tunable and would tune nothing." );
			return;
		}

		System.out.println( "  all " + EnvConfigBinder.KEYS.size() + " documented keys change the setting"
				+ " they name" );
	}

	private static void checkUnknownKeysWarn() throws IOException {
		Path file = write( "unknown-key.properties",
				"env.max_slots=11",
				"rl.not_a_real_key=1" );

		EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( file );

		if (loaded.config.maxSlots != 11){
			fail( "a file containing one unknown key did not apply its known key: maxSlots="
					+ loaded.config.maxSlots + ". An unknown key either aborted the load or replaced"
					+ " something else." );
			return;
		}

		String named = null;
		for (String warning : loaded.warnings){
			if (warning.contains( "rl.not_a_real_key" )) named = warning;
		}

		if (named == null){
			fail( "an unknown key was accepted without a warning naming it. It tunes nothing, and an"
					+ " operator who mistyped a key has no way to find that out short of noticing that"
					+ " their setting never took effect. Warnings seen: " + loaded.warnings );
			return;
		}
		if (!named.contains( file.toString() )){
			fail( "the warning for an unknown key did not name the file it came from: " + named );
			return;
		}

		System.out.println( "  an unknown key warns naming itself and its file, and does not disturb the"
				+ " keys around it" );
	}

	private static void checkBadValuesAreRefused() throws IOException {
		expectRefusal( "a non-numeric integer", "env.max_slots=wide", "env.max_slots" );
		expectRefusal( "a non-numeric float", "rl.learning_rate=fast", "rl.learning_rate" );
		expectRefusal( "a non-boolean flag", "env.allow_equipping=maybe", "env.allow_equipping" );
		expectRefusal( "a gamma outside (0, 1]", "rl.gamma=2.0", "rl.gamma" );
		expectRefusal( "a negative clip range", "rl.clip_epsilon=-0.1", "rl.clip_epsilon" );
		expectRefusal( "a zero minibatch", "rl.minibatch_size=0", "rl.minibatch_size" );
		expectRefusal( "a sample rate above 1", "rl.sample_rate=1.5", "rl.sample_rate" );
		expectRefusal( "a non-positive stall timeout", "rl.stall_seconds=0", "rl.stall_seconds" );

		System.out.println( "  malformed and out-of-range values are refused, naming the key and the file" );
	}

	private static void expectRefusal( String what, String line, String expectedKey ) throws IOException {
		Path file = write( "bad.properties", line );

		try {
			EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( file );
			fail( what + " was accepted from \"" + line + "\" - the run would proceed with a"
					+ " configuration nobody asked for (maxSlots=" + loaded.config.maxSlots
					+ ", learningRate=" + loaded.hyper.learningRate + ", gamma=" + loaded.hyper.gamma + ")." );
		} catch (IllegalArgumentException e){
			String message = e.getMessage();
			if (message == null || !message.contains( expectedKey )){
				fail( what + " was refused, but the message did not name '" + expectedKey
						+ "': " + message + ". A message that does not name the key leaves the operator to"
						+ " work out which of forty settings is wrong." );
			}
		}
	}

	private static void checkOutputPathsFollowTheWorkingDirectory() throws IOException {
		File dir = new File( scratch, "outdir" );

		TrainOptions plain = TrainOptions.parse( new String[]{ "--out", dir.getPath() });
		expectUnder( "--out without --save moves the checkpoint", plain.save, dir, "weights.bin" );
		expectUnder( "--out without --metrics moves the history", plain.metricsCsv, dir, "metrics.csv" );

		File explicit = new File( scratch, "chosen-by-hand.bin" );
		TrainOptions pinned = TrainOptions.parse( new String[]{
				"--out", dir.getPath(), "--save", explicit.getPath() } );
		if (!pinned.save.equals( explicit )) {
			fail( "--out moved an explicitly named --save to " + pinned.save
					+ ". A path somebody typed is theirs to choose, and one that moved itself would be a"
					+ " worse bug than the one being fixed here." );
			return;
		}

		TrainOptions bare = TrainOptions.parse( new String[ 0 ] );
		if (bare.save == null || bare.metricsCsv == null){
			fail( "a run with no --out has no checkpoint path (" + bare.save + ") or no metrics path ("
					+ bare.metricsCsv + ")" );
			return;
		}

		System.out.println( "  --out moves the checkpoint and the metrics history, and an explicit"
				+ " --save still wins" );
	}

	private static void checkDotEnvIsRead() throws IOException {
		java.io.File dir = new java.io.File( scratch, "dotenv" );
		dir.mkdirs();

		java.nio.file.Path file = new java.io.File( dir, "run.properties" ).toPath();
		java.nio.file.Files.writeString( file, "env.turn_limit_per_floor=1500\n" );

		java.nio.file.Files.writeString( new java.io.File( dir, ".env" ).toPath(),
				"# a comment\n"
				+ "SPD_ENV_TURN_LIMIT_PER_FLOOR=321\n"
				+ "SPD_RL_GAMMA=\"0.5\"\n"
				+ "NOT_OURS=1\n"
				+ "malformed line\n" );

		EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( file );

		if (loaded.config.turnLimitPerFloor != 321){
			fail( ".env beside the properties file was not read: turn_limit_per_floor is "
					+ loaded.config.turnLimitPerFloor + ", not the 321 it names." );
			return;
		}

		if (Math.abs( loaded.hyper.gamma - 0.5f ) > 1e-6){
			fail( "a quoted .env value was not unquoted: gamma is " + loaded.hyper.gamma + ", not 0.5." );
			return;
		}

		boolean sawForeign = false;
		boolean sawMalformed = false;
		for (String warning : loaded.warnings){
			if (warning.contains( "NOT_OURS" )) sawForeign = true;
			if (warning.contains( "malformed line" )) sawMalformed = true;
		}

		if (!sawForeign || !sawMalformed){
			fail( "a .env entry that is not SPD_ prefixed, and a line with no '=', should both warn."
					+ " sawForeign=" + sawForeign + " sawMalformed=" + sawMalformed );
			return;
		}

		System.out.println( "  .env is read beside the properties file: quoted values unquoted, foreign"
				+ " and malformed entries warned about" );
	}

	private static void checkDotEnvBeatsThePropertiesFile() throws IOException {
		java.io.File dir = new java.io.File( scratch, "dotenv-order" );
		dir.mkdirs();

		java.nio.file.Path file = new java.io.File( dir, "run.properties" ).toPath();
		java.nio.file.Files.writeString( file, "env.stall_limit=111\n" );
		java.nio.file.Files.writeString( new java.io.File( dir, ".env" ).toPath(),
				"SPD_ENV_STALL_LIMIT=222\n" );

		EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( file );

		if (loaded.config.stallLimit != 222){
			fail( ".env names stall_limit 222 and the properties file names 111, but the run used "
					+ loaded.config.stallLimit + ". The documented order is defaults, file, .env,"
					+ " process environment." );
			return;
		}

		System.out.println( "  .env outranks the properties file" );
	}

	private static void expectUnder( String what, java.io.File actual, java.io.File dir, String name ){
		java.io.File expected = new java.io.File( dir, name );
		if (expected.equals( actual )) return;
		fail( what + ": expected " + expected + ", got " + actual + ". A run pointed at --out writes part"
				+ " of its output somewhere else, and nothing reports where the rest went." );
	}

	private static String compiledValue( String key, EnvConfig config, PpoHyperparameters hyper ){
		switch (key ) {
			case "env.max_slots":             return String.valueOf( config.maxSlots );
			case "env.grid_width":            return String.valueOf( config.gridWidth );
			case "env.grid_height":           return String.valueOf( config.gridHeight );
			case "env.turn_limit_per_floor":  return String.valueOf( config.turnLimitPerFloor );
			case "env.turn_limit_total":      return String.valueOf( config.turnLimitTotal );
			case "env.actor_step_limit":      return String.valueOf( config.actorStepLimit );
			case "env.stall_limit":           return String.valueOf( config.stallLimit );
			case "env.free_step_budget":     return String.valueOf( config.freeStepBudget );
			case "env.max_view_distance":     return String.valueOf( config.maxViewDistance );
			case "env.honour_vision_buffs":   return String.valueOf( config.honourVisionBuffs );
			case "env.mimics_visible_out_of_fog":
				return String.valueOf( config.mimicsVisibleOutOfFog );
			case "env.allow_equipping":       return String.valueOf( config.allowEquipping );
			case "env.allow_trading":         return String.valueOf( config.allowTrading );
			case "env.allow_alchemy":         return String.valueOf( config.allowAlchemy );
			case "env.persist_saves":         return String.valueOf( config.persistSaves );

			case "reward.profile":            return config.rewardProfile.key();
			case "reward.turn_cost":          return String.valueOf( config.turnCost );
			case "reward.depth_reward":       return String.valueOf( config.depthReward );
			case "reward.explore_reward":     return String.valueOf( config.exploreReward );
			case "reward.max_hp_reward":      return String.valueOf( config.maxHpReward );
			case "reward.talent_reward":      return String.valueOf( config.talentReward );
			case "reward.gold_reward":        return String.valueOf( config.goldReward );
			case "reward.damage_penalty":     return String.valueOf( config.damagePenalty );
			case "reward.kill_reward":       return String.valueOf( config.killReward );
			case "reward.sneak_attack_reward":
				return String.valueOf( config.sneakAttackReward );
			case "reward.free_step_cost_factor":
				return String.valueOf( config.freeStepCostFactor );
			case "reward.death_penalty":      return String.valueOf( config.deathPenalty );
			case "reward.stall_penalty":      return String.valueOf( config.stallPenalty );
			case "reward.turn_limit_penalty": return String.valueOf( config.turnLimitPenalty );
			case "reward.victory_reward":     return String.valueOf( config.victoryReward );
			case "reward.curriculum_progress": return String.valueOf( config.curriculumProgress );

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

			default: return null;
		}
	}

	private static String sentinelFor( String key, String current ){
		if (isBooleanKey( key )){
			return current.equals( "true" ) ? "false" : "true";
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
			case "env.persist_saves":
				return true;
			default:
				return false;
		}
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
			case "env.free_step_budget":     return "77";
			case "env.max_view_distance":     return "7";
			case "rl.epochs":                 return "3";
			case "rl.minibatch_size":         return "16";
			case "rl.update_threads":         return "2";
			case "rl.max_sampled_per_episode": return "128";
			case "rl.max_samples_per_generation": return "512";
			case "rl.stall_seconds":          return "60";

			case "reward.profile":            return "depth_only";
			case "reward.turn_cost":          return "0.5";
			case "reward.depth_reward":       return "77.5";
			case "reward.explore_reward":     return "0.5";
			case "reward.max_hp_reward":      return "1.5";
			case "reward.talent_reward":      return "7.5";
			case "reward.gold_reward":        return "0.5";
			case "reward.damage_penalty":     return "0.5";
			case "reward.kill_reward":       return "1.5";
			case "reward.sneak_attack_reward": return "2.5";
			case "reward.free_step_cost_factor": return "0.9";
			case "reward.death_penalty":      return "250.0";
			case "reward.stall_penalty":      return "350.0";
			case "reward.turn_limit_penalty": return "75.0";
			case "reward.victory_reward":     return "2000.0";
			case "reward.curriculum_progress": return "0.5";
			case "rl.learning_rate":          return "0.007";
			case "rl.gamma":                  return "0.5";
			case "rl.gae_lambda":             return "0.5";
			case "rl.clip_epsilon":           return "0.1";
			case "rl.entropy_coefficient":    return "0.5";
			case "rl.sample_rate":            return "0.5";

			default:
				throw new IllegalStateException( "no sentinel for key '" + key
						+ "'. Add one, or this key cannot be proven reachable." );
		}
	}

	private static boolean sameValue( String a, String b ){
		if (a == null || b == null) return a == null && b == null;

		try {
			double x = Double.parseDouble( a.trim() );
			double y = Double.parseDouble( b.trim() );
			return Math.abs( x - y ) <= 1e-9 * Math.max( 1.0, Math.abs( y ) );
		} catch (NumberFormatException e){
			return a.trim().equals( b.trim() );
		}
	}

	private static Path shippedFile(){
		Path direct = new File( "superintelligence/superintelligence.properties" ).toPath();
		if (Files.isReadable( direct )) return direct;

		Path fromRoot = new File( "superintelligence.properties" ).toPath();
		if (Files.isReadable( fromRoot )) return fromRoot;

		throw new IllegalStateException( "cannot find superintelligence.properties from "
				+ new File( "." ).getAbsolutePath()
				+ ". It is a committed file next to build.gradle; this check cannot pass without it." );
	}

	private static Map<String, String> read( Path file ) throws IOException {
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

	private static Path write( String name, String... lines ) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) sb.append( line ).append( '\n' );
		Path path = new File( scratch, name ).toPath();
		Files.write( path, sb.toString().getBytes( StandardCharsets.UTF_8 ) );
		return path;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}