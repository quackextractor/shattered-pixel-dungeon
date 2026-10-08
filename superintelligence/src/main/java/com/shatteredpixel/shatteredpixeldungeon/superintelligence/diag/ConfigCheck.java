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

/**
 * Asserts that the configuration binder does what it says, and refuses to be the reason a run is
 * misconfigured.
 *
 * <pre>gradle :superintelligence:configcheck</pre>
 *
 * <p><b>Why this is a gate.</b> Every other check asserts on behaviour - a gradient matches a finite
 * difference, a replay reproduces, a reset isolates. This one asserts on the layer that feeds all of them,
 * and that layer fails in the worst possible way: silently. A misspelled key tunes nothing and the run
 * looks fine at a learning rate nobody chose. An unparseable value silently defaulted is worse still,
 * because it is a confident number that does not mean what the operator wrote.
 *
 * <p><b>Every other check here is a positive control first.</b> All of them assert that a configuration
 * value <em>changed something</em>. A binder that ignored its input entirely would leave every assertion
 * satisfied by the defaults alone, and would pass with a perfect score while being incapable of
 * configuring anything - which is the failure this gate most needs to be unable to have. So the first
 * check requires that a deliberately wrong value is visibly wrong, and only then are the defaults
 * compared.
 */
public class ConfigCheck {

	private static final int CHECKS = 7;

	private static final List<String> failures = new ArrayList<>();

	/** Scratch directory for the fixture files this check writes. */
	private static File scratch;

	/** Prints each warning the binder emits, so a passing run still shows what it had to say. */
	private static boolean verbose = false;

	/**
	 * No arguments, by design.
	 *
	 * <p>This check needs fixtures on disk and, for the environment-variable half, values that cannot be
	 * set from inside a running JVM. Both are awkward to express as flags and neither belongs on a CLI a
	 * user might reasonably try to drive, so the gradle task calls this directly. Everything it needs, it
	 * builds.
	 */
	public static void main( String[] args ) throws IOException {
		if (args.length > 0 && (args[ 0 ].equals( "--verbose" ) || args[ 0 ].equals( "-v" ) )){
			verbose = true;
		}

		scratch = new File( System.getProperty( "java.io.tmpdir" ), "spd-configcheck" );
		//noinspection ResultOfMethodCallIgnored
		scratch.mkdirs();

		checkTheBinderActuallyBinds();
		checkTheShippedFileUsesOnlyKnownKeys();
		checkTheShippedFileMatchesTheCompiledDefaults();
		checkEveryKnownKeyIsReachable();
		checkUnknownKeysWarn();
		checkBadValuesAreRefused();
		checkOutputPathsFollowTheWorkingDirectory();

		if (failures.isEmpty()){
			System.out.println( "[OK]     configuration binding: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  configuration binding: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	// --------------------------------------------------------------------------- checks

	/**
	 * The positive control: a wrong value in a file has to produce a wrong setting.
	 *
	 * <p>Everything below compares against the compiled defaults, so without this a binder that parsed
	 * nothing at all would pass every one of them. Asserted across both halves - the environment config
	 * and the learning hyperparameters - because they are separate objects with separate key spaces and
	 * either could be wired up while the other is not.
	 */
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

	/**
	 * The shipped file may only name keys the binder implements.
	 *
	 * <p>The failure this catches is a key added to the properties file and spelled slightly wrong - or
	 * spelled for a version of the code that no longer exists. It tunes nothing, it is not reported as
	 * unknown because the binder accepts it, and the operator has no way to find out short of noticing
	 * that their learning rate never changed.
	 */
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

	/**
	 * Every value in the shipped file must equal the compiled default.
	 *
	 * <p>The file documents the defaults and nothing else, so a value that differs is a comment in
	 * properties-file clothing: it says the default is one thing and the code uses another, and a reader
	 * tuning from the file is tuning from the wrong number. This is the assertion that keeps the two from
	 * drifting, which is the whole reason the file exists.
	 *
	 * <p>Both directions matter, and both are checked: a key in the file whose value has moved away from
	 * the code, and a key the code changed whose value in the file was not updated.
	 */
	private static void checkTheShippedFileMatchesTheCompiledDefaults() throws IOException {
		Path shipped = shippedFile();
		Map<String, String> values = read( shipped );

		//loaded from an empty file, so these are the compiled defaults and nothing else
		EnvConfigBinder.Loaded defaults = EnvConfigBinder.load( null );
		EnvConfig config = defaults.config;
		PpoHyperparameters hyper = defaults.hyper;

		List<String> mismatched = new ArrayList<>();

		for (Map.Entry<String, String> entry : values.entrySet()){
			String key = entry.getKey();
			String written = entry.getValue().trim();

			String actual = compiledValue( key, config, hyper );
			if (actual == null){
				//already reported by the key check above; skipped here so one typo produces one message
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

	/**
	 * Every key the binder accepts must actually change something.
	 *
	 * <p>{@link EnvConfigBinder#KEYS} is a list, and {@code apply}'s switch is a second list. A key
	 * added to the first and forgotten in the second would throw - which is why {@code apply}'s default
	 * branch throws rather than ignoring - but only when it is <em>present in a file</em>. This drives all
	 * of them, so a key that is accepted, documented and dead is caught here instead of by an operator
	 * who tuned it.
	 *
	 * <p>Each key gets a value deliberately unlike its default, so "changed" is a real assertion rather
	 * than a comparison that happens to hold.
	 */
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

	/**
	 * An unrecognised key warns, names itself, and does not disturb the keys around it.
	 *
	 * <p>A warning rather than an error, because a shared properties file may legitimately carry keys for
	 * other tools, and refusing to start over one would be worse than the problem. But it has to say
	 * <em>which</em> key and <em>which</em> file, or it is not actionable - and that is the only observable
	 * difference between a binder that warns and one that silently ignores. So the warning is asserted as
	 * data, through {@link EnvConfigBinder.Loaded#warnings}, rather than by capturing stderr: capturing a
	 * process's output would make the check assert on its own harness, and the collection exists precisely
	 * so this assertion can be made on the value.
	 *
	 * <p>Both halves are needed. A binder that aborted the load would be equally silent about the mistake
	 * from the operator's side - the run would use defaults and no warning would name the bad key - so the
	 * known key beside the unknown one has to survive.
	 */
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

	/**
	 * A value that cannot be read is refused, and the message names the key, the file and the text.
	 *
	 * <p>The single most important property of this layer. Defaulting an unparseable value hands back a
	 * configuration nobody asked for, in a system whose entire value is that its settings are the ones it
	 * reports - and it fails silently, which is the worst combination available.
	 *
	 * <p>Checked for the numeric, boolean and out-of-range classes separately, because they are three
	 * different code paths and a binder that catches only {@code NumberFormatException} still lets
	 * {@code rl.gamma=2.0} through to a discount factor that never decays.
	 */
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

	// --------------------------------------------------------------------------- helpers

	/**
	 * {@code --out} must move every default output, not just some of them.
	 *
	 * <p>The checkpoint and the metrics history are derived from the working directory, and both used to
	 * be derived in the constructor - before the command line is read. So {@code --out D} put the
	 * replays in D while leaving a 43 MB checkpoint and a metrics history in the old directory, and the
	 * run reported saving to a path nobody had named. Half a run's output in the place that was asked
	 * for and half somewhere else is worse than none of it in either.
	 *
	 * <p>Asserted on the parsed options rather than on a real run: this is about where a path points,
	 * and a run that had to actually write a checkpoint to prove it would take a minute and write tens of
	 * megabytes to do it. The arithmetic is the whole of the behaviour.
	 *
	 * <p>Both halves matter, and the second is the reason this is not simply "the default follows
	 * {@code --out}": an explicit {@code --save} is a deliberate path and must not move. A fix that
	 * re-derived it unconditionally would pass the first assertion and quietly break the second.
	 */
	private static void checkOutputPathsFollowTheWorkingDirectory() throws IOException {
		File dir = new File( scratch, "outdir" );

		TrainOptions plain = TrainOptions.parse( new String[]{ "--out", dir.getPath() });
		expectUnder( "--out without --save moves the checkpoint", plain.save, dir, "weights.bin" );
		expectUnder( "--out without --metrics moves the history", plain.metricsCsv, dir, "metrics.csv" );

		//an explicit path is the operator's decision and has to survive --out
		File explicit = new File( scratch, "chosen-by-hand.bin" );
		TrainOptions pinned = TrainOptions.parse( new String[]{
				"--out", dir.getPath(), "--save", explicit.getPath() } );
		if (!pinned.save.equals( explicit )) {
			fail( "--out moved an explicitly named --save to " + pinned.save
					+ ". A path somebody typed is theirs to choose, and one that moved itself would be a"
					+ " worse bug than the one being fixed here." );
			return;
		}

		//and no --out at all still lands somewhere real rather than null
		TrainOptions bare = TrainOptions.parse( new String[ 0 ] );
		if (bare.save == null || bare.metricsCsv == null){
			fail( "a run with no --out has no checkpoint path (" + bare.save + ") or no metrics path ("
					+ bare.metricsCsv + ")" );
			return;
		}

		System.out.println( "  --out moves the checkpoint and the metrics history, and an explicit"
				+ " --save still wins" );
	}

	private static void expectUnder( String what, java.io.File actual, java.io.File dir, String name ){
		java.io.File expected = new java.io.File( dir, name );
		if (expected.equals( actual )) return;
		fail( what + ": expected " + expected + ", got " + actual + ". A run pointed at --out writes part"
				+ " of its output somewhere else, and nothing reports where the rest went." );
	}

	/**
	 * The value of one key in a loaded configuration, or null if the binder has no such key.
	 *
	 * <p>A third list, and deliberately not reflection: reflection would make the check pass for any key
	 * the binder happens to handle and silently skip any it does not, which is exactly the bug being
	 * looked for. Written out, it fails to compile when a setting is added and this is not updated.
	 */
	private static String compiledValue( String key, EnvConfig config, PpoHyperparameters hyper ){
		switch (key ) {
			case "env.max_slots":             return String.valueOf( config.maxSlots );
			case "env.grid_width":            return String.valueOf( config.gridWidth );
			case "env.grid_height":           return String.valueOf( config.gridHeight );
			case "env.turn_limit_per_floor":  return String.valueOf( config.turnLimitPerFloor );
			case "env.turn_limit_total":      return String.valueOf( config.turnLimitTotal );
			case "env.actor_step_limit":      return String.valueOf( config.actorStepLimit );
			case "env.stall_limit":           return String.valueOf( config.stallLimit );
			case "env.max_view_distance":     return String.valueOf( config.maxViewDistance );
			case "env.honour_vision_buffs":   return String.valueOf( config.honourVisionBuffs );
			case "env.mimics_visible_out_of_fog":
				return String.valueOf( config.mimicsVisibleOutOfFog );
			case "env.allow_equipping":       return String.valueOf( config.allowEquipping );
			case "env.allow_trading":         return String.valueOf( config.allowTrading );
			case "env.allow_alchemy":         return String.valueOf( config.allowAlchemy );
			case "env.persist_saves":         return String.valueOf( config.persistSaves );

			case "reward.turn_cost":          return String.valueOf( config.turnCost );
			case "reward.depth_reward":       return String.valueOf( config.depthReward );
			case "reward.explore_reward":     return String.valueOf( config.exploreReward );
			case "reward.max_hp_reward":      return String.valueOf( config.maxHpReward );
			case "reward.gold_reward":        return String.valueOf( config.goldReward );
			case "reward.damage_penalty":     return String.valueOf( config.damagePenalty );
			case "reward.death_penalty":      return String.valueOf( config.deathPenalty );
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

	/**
	 * A value for each key that is valid, and unlike its default.
	 *
	 * <p>Valid, because an invalid one would be refused before the "did it change anything" question could
	 * be asked - and {@link #checkBadValuesAreRefused()} would then be the only thing testing it, which is
	 * the wrong place to discover that the reachability of a key depends on its range being wide.
	 *
	 * <p>Unlike its default, and that is why the defaults are passed in rather than hardcoded: the first
	 * version of this returned {@code false} for every boolean, which is the opposite of every boolean
	 * default except one - so {@code env.persist_saves} looked inert and the check reported a key as dead
	 * that is not. A sentinel chosen by assumption about the default is the same bug as one written out by
	 * hand, only harder to see.
	 */
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

	/**
	 * A valid in-range value for every non-boolean key.
	 *
	 * <p>Distinct from its default is not checked here - {@link #sentinelFor} handles that by falling
	 * through to the boolean rule or by the reachability check noticing no change - but every value below
	 * is chosen to sit inside the range {@link PpoHyperparameters#validate()} and the binder's own type
	 * checks accept, so a key cannot look dead merely because its sentinel was rejected.
	 */
	private static String numericSentinelFor( String key ){
		switch ( key ) {
			//integers that must stay in a range
			case "env.max_slots":             return "9";
			case "env.grid_width":            return "24";
			case "env.grid_height":           return "24";
			case "env.turn_limit_per_floor":  return "777";
			case "env.turn_limit_total":      return "31337";
			case "env.actor_step_limit":      return "555";
			case "env.stall_limit":           return "42";
			case "env.max_view_distance":     return "7";
			case "rl.epochs":                 return "3";
			case "rl.minibatch_size":         return "16";
			case "rl.update_threads":         return "2";
			case "rl.max_sampled_per_episode": return "128";
			case "rl.max_samples_per_generation": return "512";
			case "rl.stall_seconds":          return "60";

			//floats that must stay in a range
			case "reward.turn_cost":          return "0.5";
			case "reward.depth_reward":       return "77.5";
			case "reward.explore_reward":     return "0.5";
			case "reward.max_hp_reward":      return "1.5";
			case "reward.gold_reward":        return "0.5";
			case "reward.damage_penalty":     return "0.5";
			case "reward.death_penalty":      return "250.0";
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

	/**
	 * Whether two recorded values are the same setting.
	 *
	 * <p>Numerically for numbers, not by their printed text. {@code 3e-4f} prints as {@code 3.0E-4} and
	 * the properties file says {@code 0.0003}, and string comparison reported a disagreement between two
	 * spellings of the same float - which would have made this check fail on any key whose default is
	 * written in exponent form, for no reason at all. Comparing as doubles with a tolerance relative to
	 * the magnitude is what the assertion actually means.
	 *
	 * <p>Falls back to string equality for anything not parseable as a number, which is the booleans.
	 */
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

	/** The shipped properties file, found relative to the module rather than the working directory. */
	private static Path shippedFile(){
		Path direct = new File( "superintelligence/superintelligence.properties" ).toPath();
		if (Files.isReadable( direct )) return direct;

		Path fromRoot = new File( "superintelligence.properties" ).toPath();
		if (Files.isReadable( fromRoot )) return fromRoot;

		throw new IllegalStateException( "cannot find superintelligence.properties from "
				+ new File( "." ).getAbsolutePath()
				+ ". It is a committed file next to build.gradle; this check cannot pass without it." );
	}

	/** A properties file's keys and values, in file order, with comments and blanks dropped. */
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