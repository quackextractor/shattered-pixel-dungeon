package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class CheckpointCheck {

	private static final int CHECKS = 11;

	private static void checkARunThatTrainedNothingLeavesTheCheckpointAlone( Network trained, EnvConfig config,
			File dir ) throws IOException {
		File file = new File( dir, "kept.bin" );
		Checkpoint.save( file, trained, config, 61, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );
		long before = file.length();
		long modified = file.lastModified();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer trainer =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer( config, 1L, dir );
		trainer.checkpointing( file, 1 );
		trainer.saveOnExit();
		trainer.closeUpdate();
		int generation = Checkpoint.load( file, new Network( config, new Random( 3L )), config ).generation;
		if (generation != 61 || file.length() != before || file.lastModified() != modified){
			fail( "a run that failed before its first generation overwrote a generation-61 checkpoint with"
					+ " generation " + generation + "; the fresh-1022 restart lost seeds100-60 that way" );
			return;
		}
		System.out.println( "  a run that trained nothing leaves the checkpoint it was told to save to alone" );
	}

	public static void main( String[] args ){
		File dir = Scratch.dir( "checkpointcheck" );

		try {
			EnvConfig config = new EnvConfig();

			EnvConfig small = new EnvConfig();
			small.gridWidth = 12;
			small.gridHeight = 12;
			small.maxSlots = 4;

			Network original = new Network( small, new Random( 20261007L ) );

			touch( original );

			File file = new File( dir, "weights.bin" );

			checkRoundTripIsExact( original, small, file, 7, 4242L );
			checkRefusesForeignFile( small, new File( dir, "foreign.bin" ) );
			checkRefusesTruncatedFile( original, small, file, new File( dir, "truncated.bin" ) );
			checkRefusesTrailingBytes( original, small, file, new File( dir, "trailing.bin" ) );
			checkRefusesDifferentConfig( original, small, file );
			checkLeavesNoTemporaryFiles( original, small, file, dir );
			checkResumesIntoAnUntrainedNetwork( original, small, file );
			checkReportsGenerationAndSteps( original, small, file, 7, 4242L );
			checkAMissingFlagValueIsRefusedNamingItself();
			checkCheckpointEveryComesFromTheFileUnlessTheFlagSaysOtherwise( dir );
			checkARunThatTrainedNothingLeavesTheCheckpointAlone( original, small, dir );

			Checkpoint.save( new File( dir, "default.bin" ),
				new Network( config, new Random( 1L ) ), config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );
			File defaultFile = new File( dir, "default.bin" );
			Checkpoint.load( defaultFile, new Network( config, new Random( 2L ) ), config );

		} catch (IOException e){
			fail( "the check threw: " + e.getMessage() );
		}

		System.exit( GateReport.report( "checkpoints", CHECKS ));
	}

	private static void checkRoundTripIsExact( Network original, EnvConfig config, File file,
			int generation, long seed ) throws IOException {
		Checkpoint.save( file, original, config, generation, seed, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );

		Network restored = new Network( config, new Random( 999L ) );
		Checkpoint.load( file, restored, config );

		Network.Layer[] before = original.layers();
		Network.Layer[] after = restored.layers();
		if (before.length != after.length){
			fail( "layer count changed across the round trip: " + before.length + " to " + after.length );
			return;
		}

		for (int i = 0; i < before.length; i++){
			if (!before[ i ].name.equals( after[ i ].name )) continue;

			if (!java.util.Arrays.equals( before[ i ].weights, after[ i ].weights )){
				fail( "layer " + before[ i ].name + " did not survive exactly" );
				return;
			}
			if (!java.util.Arrays.equals( before[ i ].bias, after[ i ].bias )){
				fail( "layer " + before[ i ].name + "'s bias did not survive exactly" );
				return;
			}
		}

		Network.Moments[] mBefore = original.moments();
		Network.Moments[] mAfter = restored.moments();
		for (int i = 0; i < mBefore.length; i++){
			String name = mBefore[ i ].name;
			if (!java.util.Arrays.equals( mBefore[ i ].mW, moment( mAfter, name, "mW" ))
					|| !java.util.Arrays.equals( mBefore[ i ].vW, moment( mAfter, name, "vW" ))
					|| !java.util.Arrays.equals( mBefore[ i ].mb, moment( mAfter, name, "mb" ))
					|| !java.util.Arrays.equals( mBefore[ i ].vb, moment( mAfter, name, "vb" ))){
				fail( "Adam moments for " + name + " did not survive. Without these a resumed run"
						+ " restarts its optimiser from zero and trains worse for no visible reason." );
				return;
			}
		}

		if (restored.adamSteps() != original.adamSteps()){
			fail( "the optimiser step count came back as " + restored.adamSteps()
					+ " instead of " + original.adamSteps() + ". Adam's bias correction divides by"
					+ " 1-beta^step, so this silently changes the first steps after a resume." );
		}
	}

	private static float[] moment( Network.Moments[] moments, String name, String field ){
		for (Network.Moments m : moments){
			if (!m.name.equals( name )) continue;
			switch (field){
				case "mW": return m.mW;
				case "vW": return m.vW;
				case "mb": return m.mb;
				default:   return m.vb;
			}
		}
		fail( "no moment group named " + name );
		return new float[ 0 ];
	}

	private static void checkRefusesForeignFile( EnvConfig config, File file ) throws IOException {
		java.nio.file.Files.write( file.toPath(),
				"this is a text file, not a policy".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );

		String error = refusal( file, config );
		if (error == null){
			fail( "a text file was accepted as a policy. Resuming from one would train a network of"
					+ " whatever those bytes happened to look like." );
			return;
		}
		if (!error.contains( "magic" )){
			fail( "a foreign file was refused, but not as a magic mismatch: " + error );
		}
	}

	private static void checkRefusesTruncatedFile( Network original, EnvConfig config,
			File file, File truncated ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );
		java.nio.file.Files.copy( file.toPath(), truncated.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING );

		long full = truncated.length();
		try (RandomAccessFile raf = new RandomAccessFile( truncated, "rw" )){
			raf.setLength( full - 4096 );
		}

		String error = refusal( truncated, config );
		if (error == null){
			fail( "a checkpoint truncated by " + (full - 4096) + " of " + full
					+ " bytes was accepted" );
		}
	}

	private static void checkRefusesTrailingBytes( Network original, EnvConfig config,
			File file, File trailing ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );
		java.nio.file.Files.copy( file.toPath(), trailing.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING );

		try (RandomAccessFile raf = new RandomAccessFile( trailing, "rw" )){
			raf.seek( raf.length() );
			raf.write( new byte[]{ 1, 2, 3, 4, 5 } );
		}

		String error = refusal( trailing, config );
		if (error == null){
			fail( "a checkpoint with 5 trailing bytes was accepted, so a format that had grown would"
					+ " be silently misread by an older build" );
		}
	}

	private static void checkRefusesDifferentConfig( Network original, EnvConfig config,
			File file ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );

		checkMismatchNames( config, file, "maxSlots", 6 );
		checkMismatchNames( config, file, "gridWidth", 10 );
		checkMismatchNames( config, file, "gridHeight", 10 );
	}

	private static void checkMismatchNames( EnvConfig config, File file,
			String field, int newValue ) throws IOException {
		EnvConfig changed = new EnvConfig();
		changed.maxSlots = config.maxSlots;
		changed.gridWidth = config.gridWidth;
		changed.gridHeight = config.gridHeight;
		switch (field){
			case "maxSlots":   changed.maxSlots = newValue;   break;
			case "gridWidth":  changed.gridWidth = newValue;  break;
			default:           changed.gridHeight = newValue; break;
		}

		String error = null;
		try {
			Checkpoint.load( file, new Network( changed, new Random( 3L ) ), changed );
		} catch (IOException e){
			error = e.getMessage();
		}

		if (error == null){
			fail( "a checkpoint trained with " + field + "=" + fieldValue( config, field )
					+ " was loaded into a network with " + field + "=" + newValue );
			return;
		}
		if (!error.contains( field )){
			fail( "changing " + field + " was refused, but the message named something else: " + error );
		}
	}

	private static int fieldValue( EnvConfig config, String field ){
		switch (field){
			case "maxSlots":  return config.maxSlots;
			case "gridWidth": return config.gridWidth;
			default:          return config.gridHeight;
		}
	}

	private static void checkLeavesNoTemporaryFiles( Network original, EnvConfig config,
			File file, File dir ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );
		Checkpoint.save( file, original, config, 2, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );

		File[] strays = dir.listFiles( (d, name) -> name.startsWith( "weights-" ) );
		if (strays != null && strays.length > 0){
			fail( strays.length + " temporary checkpoint file(s) left behind, starting with "
					+ strays[ 0 ].getName() + ". The save is not cleaning up its own temp file." );
		}
	}

	private static void checkResumesIntoAnUntrainedNetwork( Network original, EnvConfig config,
			File file ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L, 11L, 13L, 17L, 4, 4, 0f, 1f, false, 0L );

		Network target = new Network( config, new Random( 555L ) );
		Network.Layer[] live = target.layers();
		float[][] initial = new float[ live.length ][];
		for (int i = 0; i < live.length; i++) initial[ i ] = live[ i ].weights.clone();

		Checkpoint.load( file, target, config );
		Network.Layer[] restored = target.layers();
		Network.Layer[] saved = original.layers();

		for (int i = 0; i < restored.length; i++){
			if (!java.util.Arrays.equals( restored[ i ].weights, saved[ i ].weights )
					|| java.util.Arrays.equals( restored[ i ].weights, initial[ i ] )){
				fail( "loading did not overwrite " + restored[ i ].name
						+ ", so a resume would keep part of the random initialisation" );
				return;
			}
		}
	}

	private static void checkReportsGenerationAndSteps( Network original, EnvConfig config,
			File file, int generation, long seed ) throws IOException {
		Checkpoint.save( file, original, config, generation, seed, 11L, 13L, 17L, 4, 4,
				2.5f, 3.25f, true, 99L );
		Checkpoint.Meta meta = Checkpoint.load( file,
				new Network( config, new Random( 4L ) ), config );

		if (meta.generation != generation){
			fail( "generation came back as " + meta.generation + ", expected " + generation );
			return;
		}
		if (meta.trainSeed != seed){
			fail( "the training seed came back as " + meta.trainSeed + ", expected " + seed );
			return;
		}
		if (meta.seedPoolSeed != 11L || meta.seedScheduleSeed != 13L
				|| meta.seedDraws != 17L || meta.activeSeeds != 4 ){
			fail( "the seed schedule came back as pool " + meta.seedPoolSeed + ", schedule "
					+ meta.seedScheduleSeed + ", draws " + meta.seedDraws + ", active "
					+ meta.activeSeeds + " against the 11/13/17/4 that was written. A resume that does"
					+ " not carry them replays the seed sequence from generation 0." );
			return;
		}
		if (!meta.hasTrainerState || meta.valueMean != 2.5f || meta.valueStd != 3.25f
				|| !meta.valueReady || meta.rngState != 99L){
			fail( "the trainer state came back as value mean " + meta.valueMean + ", std "
					+ meta.valueStd + ", ready " + meta.valueReady + ", rng " + meta.rngState
					+ " against the 2.5/3.25/true/99 that was written. A resume without them resets the"
					+ " value normaliser and replays the trainer's draws from generation 0." );
			return;
		}
		if (meta.adamSteps != original.adamSteps()){
			fail( "the header's adam step count is " + meta.adamSteps + " but the network has "
					+ original.adamSteps() );
			return;
		}
		if (meta.gridWidth != config.gridWidth || meta.gridHeight != config.gridHeight
				|| meta.maxSlots != config.maxSlots){
			fail( "the header's config does not match the one it was saved with" );
		}
	}

	private static void checkAMissingFlagValueIsRefusedNamingItself(){
		for (String flag : new String[]{ "--resume", "--workers", "--replay-group", "--save" }){
			String message = null;
			try {
				TrainOptions.parse( new String[]{ flag });
			} catch (IllegalArgumentException e){
				message = String.valueOf( e.getMessage() );
			} catch (IOException e){
				message = "IO: " + e.getMessage();
			} catch (RuntimeException e){

				message = e.getClass().getSimpleName() + ": " + e.getMessage();
			}

			if (message == null){
				fail( "'" + flag + "' with nothing after it was accepted, so a flag that needs a path"
						+ " silently resumes from the default or from nothing." );
				return;
			}
			if (!message.contains( flag ) || !message.contains( "needs a value" )){
				fail( "'" + flag + "' with nothing after it was refused as '" + message + "', which does"
						+ " not name the flag or say what was missing" );
				return;
			}
		}

		try {
			TrainOptions.parse( new String[]{ "--workers", "2", "--not-a-real-flag" });
		} catch (IllegalArgumentException e){
			fail( "an unknown option was refused instead of warned about: " + e.getMessage()
					+ ". A refusal here would move the failure rather than catch it." );
		} catch (IOException e){
			fail( "parsing an unknown option threw: " + e.getMessage() );
		}
	}

	private static void checkCheckpointEveryComesFromTheFileUnlessTheFlagSaysOtherwise( File dir )
			throws IOException {
		File config = new File( dir, "every.properties" );
		java.nio.file.Files.write( config.toPath(),
				java.util.Arrays.asList( "train.checkpoint_every=7", "train.record_every=5" ));

		TrainOptions fromFile = TrainOptions.parse( new String[]{ "--config", config.getPath() });
		if (fromFile.checkpointEvery != 7 || fromFile.recordEvery != 5){
			fail( "a config file saying checkpoint_every=7 and record_every=5 parsed as "
					+ fromFile.checkpointEvery + " and " + fromFile.recordEvery + ". The trainer reads the"
					+ " options, so a value the options do not carry never applies." );
			return;
		}

		TrainOptions flagged = TrainOptions.parse( new String[]{ "--config", config.getPath(),
				"--checkpoint-every", "10", "--record-every", "3" });
		if (flagged.checkpointEvery != 10 || flagged.recordEvery != 3){
			fail( "--checkpoint-every 10 --record-every 3 over that file parsed as "
					+ flagged.checkpointEvery + " and " + flagged.recordEvery
					+ ". A run asked to checkpoint every 10 generations and killed at 16 saves nothing." );
		}
	}

	private static String refusal( File file, EnvConfig config ){
		try {
			Checkpoint.load( file, new Network( config, new Random( 6L ) ), config );
			return null;
		} catch (IOException e){
			return String.valueOf( e.getMessage() );
		}
	}

	private static void touch( Network net ){
		for (Network.Moments m : net.moments()){
			for (int i = 0; i < m.mW.length; i++) m.mW[ i ] = (i % 17) * 1e-4f;
			for (int i = 0; i < m.vW.length; i++) m.vW[ i ] = (i % 23) * 1e-8f;
			for (int i = 0; i < m.mb.length; i++) m.mb[ i ] = (i % 11) * 1e-4f;
			for (int i = 0; i < m.vb.length; i++) m.vb[ i ] = (i % 13) * 1e-8f;
		}
		net.adamSteps( 137 );
	}
}
