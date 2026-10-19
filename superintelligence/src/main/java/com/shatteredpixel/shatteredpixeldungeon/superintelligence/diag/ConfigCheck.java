package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.PpoHyperparameters;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.compiledValue;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.read;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.sameValue;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.sentinelFor;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.shippedFile;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.write;

public class ConfigCheck {

	private static final int CHECKS = 17;

	public static void main( String[] args ) throws IOException {
		if (args.length > 0 && (args[ 0 ].equals( "--verbose" ) || args[ 0 ].equals( "-v" ) )){
			ConfigDefaults.verbose = true;
		}

		ConfigDefaults.scratch = new File( System.getProperty( "java.io.tmpdir" ), "spd-configcheck" );

		ConfigDefaults.scratch.mkdirs();

		checkTheBinderActuallyBinds();
		checkEveryConfigFieldTravelsToAWorker();
		checkTheShippedFileUsesOnlyKnownKeys();
		checkTheShippedFileMatchesTheCompiledDefaults();
		checkEveryKnownKeyIsReachable();
		checkUnknownKeysWarn();
		checkBadValuesAreRefused();
checkOutputPathsFollowTheWorkingDirectory();
		ConfigDotEnvCheck.checkDotEnvIsRead();
		ConfigDotEnvCheck.checkDotEnvBeatsThePropertiesFile();
		ConfigDotEnvCheck.checkABadDotEnvValueNamesTheDotEnv();
		ConfigDotEnvCheck.checkALoneQuoteIsAValue();
		ConfigDotEnvCheck.checkTheExampleFileNamesEveryDocumentedKey();
		ConfigPathCheck.checkTheBuildScriptReadsTheHeapItDocuments();
		ConfigPathCheck.checkEveryPathIsReportedAbsolute();
		ConfigPathCheck.checkARelativePathResolvesAgainstTheRepository();
		ConfigPathCheck.checkTheTrainerResolvesItsOwnPathsToo();

		if (ConfigDefaults.failures.isEmpty()){
			System.out.println( "[OK]     configuration binding: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  configuration binding: " + ConfigDefaults.failures.size()
					+ " of " + CHECKS + " checks failed" );
			for (String f : ConfigDefaults.failures) System.out.println( "        " + f );
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
		probe.identifiedReward = 15.5f;  probe.curseEquippedPenalty = 16.5f;
		probe.curseRemovedReward = 17.5f; probe.equipTooStrongPenalty = 18.5f;
		probe.curedDebuffReward = 19.5f; probe.eatenEarlyPenalty = 20.5f;
		probe.menuNoopPenalty = 21.5f;   probe.levelUpReward = 22.5f;
		probe.strengthGainReward = 23.5f; probe.goldLostFactor = 24.5f;
		probe.itemValueBag = 25.5f;     probe.itemValueEquipBase = 26.5f;
		probe.itemValueEquipLevel = 27.5f; probe.itemValueOther = 28.5f;
		probe.bossSlainFactor = 29.5f;   probe.maxHpGainCap = 30.5f;
		probe.curriculumFadeStartDepth = 31; probe.curriculumFadeEndDepth = 32;
		probe.honourVisionBuffs = false;
		probe.mimicsVisibleOutOfFog = false;
		probe.allowEquipping = false;
		probe.allowTrading = false;
		probe.allowAlchemy = false;
		probe.subModeProgressOnly = false;
		probe.recordRngFingerprint = false;
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
		List< String > lost = new ArrayList<>();
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

	private static final List< String > ENV_FIELDS = java.util.Arrays.asList(
		"maxSlots", "gridWidth", "gridHeight", "turnLimitPerFloor", "turnLimitTotal", "actorStepLimit",
		"stallLimit", "freeStepBudget", "maxViewDistance", "curriculumProgress", "turnCost",
		"depthReward", "exploreReward", "maxHpReward", "talentReward", "goldReward", "damagePenalty",
		"killReward", "sneakAttackReward", "freeStepCostFactor", "deathPenalty", "stallPenalty",
		"turnLimitPenalty", "victoryReward", "honourVisionBuffs", "mimicsVisibleOutOfFog",
		"allowEquipping", "allowTrading", "allowAlchemy", "subModeProgressOnly", "recordRngFingerprint",
		"persistSaves", "rewardProfile",
		"dialogAgeSeconds", "stairPotentialWeight",
		"identifiedReward", "curseEquippedPenalty", "curseRemovedReward", "equipTooStrongPenalty",
		"curedDebuffReward", "eatenEarlyPenalty", "menuNoopPenalty", "levelUpReward",
		"strengthGainReward", "goldLostFactor", "itemValueBag", "itemValueEquipBase",
		"itemValueEquipLevel", "itemValueOther", "bossSlainFactor", "maxHpGainCap",
		"curriculumFadeStartDepth", "curriculumFadeEndDepth" );

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

		List<String> unmapped = new ArrayList<>();
		List<String> mismatched = new ArrayList<>();

		for (Map.Entry<String, String> entry : values.entrySet()){
			String key = entry.getKey();
			String written = entry.getValue().trim();

			String actual = compiledValue( key, config, hyper );
			if (actual == null){
				unmapped.add( key );
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

		if (!unmapped.isEmpty()){
			fail( "this check has no compiled value to compare " + unmapped.size() + " of the "
					+ values.size() + " keys in " + shipped.getFileName() + " against: "
					+ String.join( ", ", unmapped ) + ". Skipping a key is not the same as agreeing with"
					+ " it, and the count this check prints is the count of keys the file carries." );
			return;
		}

		if (missing.isEmpty() && mismatched.isEmpty()){
			System.out.println( "  all " + values.size() + " shipped defaults match the compiled ones, in"
					+ " both directions" );
		}
	}


	private static void checkEveryKnownKeyIsReachable() throws IOException {
		EnvConfigBinder.Loaded base = EnvConfigBinder.load( null );

		List<String> inert = new ArrayList<>();
		int examined = 0;

		for (String key : EnvConfigBinder.KEYS){
			String current = compiledValue( key, base.config, base.hyper );
			if (current == null){
				inert.add( key + " (this check has no compiled value for it, so it cannot be proven"
						+ " reachable at all)" );
				continue;
			}
			examined++;

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

		System.out.println( "  all " + examined + " documented keys change the setting they name" );
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
		File dir = new File( ConfigDefaults.scratch, "outdir" );

		TrainOptions plain = TrainOptions.parse( new String[]{ "--out", dir.getPath() });
		expectUnder( "--out without --save moves the checkpoint", plain.save, dir, "weights.bin" );
		expectUnder( "--out without --metrics moves the history", plain.metricsCsv, dir, "metrics.csv" );

		File explicit = new File( ConfigDefaults.scratch, "chosen-by-hand.bin" );
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


	private static void expectUnder( String what, File actual, File dir, String name ){
		File expected = new File( dir, name );
		if (expected.equals( actual )) return;
		fail( what + ": expected " + expected + ", got " + actual + ". A run pointed at --out writes part"
				+ " of its output somewhere else, and nothing reports where the rest went." );
	}

}