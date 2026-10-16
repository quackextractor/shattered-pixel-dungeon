package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CheckpointCheck {

	private static final int CHECKS = 9;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		File dir = new File( System.getProperty( "java.io.tmpdir" ), "spd-checkpointcheck" );

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

			Checkpoint.save( new File( dir, "default.bin" ),
					new Network( config, new Random( 1L ) ), config, 1, 1L );
			File defaultFile = new File( dir, "default.bin" );
			Checkpoint.load( defaultFile, new Network( config, new Random( 2L ) ), config );

		} catch (IOException e){
			fail( "the check threw: " + e.getMessage() );
		} finally {
			deleteAll( dir );
		}

		if (failures.isEmpty()){
			System.out.println( "[OK]     checkpoints: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  checkpoints: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkRoundTripIsExact( Network original, EnvConfig config, File file,
			int generation, long seed ) throws IOException {
		Checkpoint.save( file, original, config, generation, seed );

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
		Checkpoint.save( file, original, config, 1, 1L );
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
		Checkpoint.save( file, original, config, 1, 1L );
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
		Checkpoint.save( file, original, config, 1, 1L );

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
		Checkpoint.save( file, original, config, 1, 1L );
		Checkpoint.save( file, original, config, 2, 1L );

		File[] strays = dir.listFiles( (d, name) -> name.startsWith( "weights-" ) );
		if (strays != null && strays.length > 0){
			fail( strays.length + " temporary checkpoint file(s) left behind, starting with "
					+ strays[ 0 ].getName() + ". The save is not cleaning up its own temp file." );
		}
	}

	private static void checkResumesIntoAnUntrainedNetwork( Network original, EnvConfig config,
			File file ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L );

		Network target = new Network( config, new Random( 555L ) );
		Network.Layer[] before = target.layers();
		Network.Layer[] after = target.layers();

		boolean anyDifferent = false;
		for (int i = 0; i < before.length; i++){
			if (!java.util.Arrays.equals( before[ i ].weights, after[ i ].weights )) anyDifferent = true;
		}
		if (anyDifferent){
			fail( "a network changed between two calls to layers(), which would make this check"
					+ " meaningless" );
			return;
		}

		Checkpoint.load( file, target, config );
		Network.Layer[] restored = target.layers();

		for (int i = 0; i < restored.length; i++){
			if (!java.util.Arrays.equals( restored[ i ].weights, before[ i ].weights )){
				fail( "loading did not overwrite " + restored[ i ].name
						+ ", so a resume would keep part of the random initialisation" );
				return;
			}
		}
	}

	private static void checkReportsGenerationAndSteps( Network original, EnvConfig config,
			File file, int generation, long seed ) throws IOException {
		Checkpoint.save( file, original, config, generation, seed );
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

	private static void deleteAll( File dir ){
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) f.delete();
		dir.delete();
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
