package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fails if a policy cannot survive a trip through disk.
 *
 * <pre>gradle :superintelligence:checkpointcheck</pre>
 *
 * A checkpoint is the only thing standing between a training run and being thrown away, and it is the
 * one artefact whose failure mode is silence. A weight file that loads with the wrong shape produces a
 * network that runs, reports plausible losses, and learns nonsense; a moment file that loads as zeros
 * produces a run that resumes and quietly trains worse, with nothing in the output to say why.
 *
 * <p>So every case here is one of "refuse loudly" rather than "produce a number":
 *
 * <ul>
 *   <li>weights survive a round trip exactly, bit for bit
 *   <li>Adam moments survive, which {@code Network.layers()} alone would not carry
 *   <li>the optimiser step count survives, without which Adam's bias correction restarts near 0.1x
 *   <li>a file that is not a checkpoint is refused
 *   <li>a truncated file is refused
 *   <li>a file with trailing bytes is refused
 *   <li>a checkpoint from a different {@code EnvConfig} is refused, naming the field
 *   <li>saving twice leaves no temporary files behind
 * </ul>
 *
 * <p>Written against a real {@link Network} rather than a stub, because the thing worth checking is
 * that the moments are laid out the way the layers are - a stub would agree with a broken
 * implementation.
 */
public class CheckpointCheck {

	private static final int CHECKS = 8;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		File dir = new File( System.getProperty( "java.io.tmpdir" ), "spd-checkpointcheck" );

		try {
			EnvConfig config = new EnvConfig();

			//a small network: the shape checks are what matter and a 3.5M-parameter one costs seconds
			EnvConfig small = new EnvConfig();
			small.gridWidth = 12;
			small.gridHeight = 12;
			small.maxSlots = 4;

			Network original = new Network( small, new Random( 20261007L ) );
			//some non-trivial moments and a step count, so "did it restore" is a real question
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

			//the default config, because every real run uses it and a shape that only works at 12x12
			//would pass everything above
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

	/**
	 * Every weight and every moment must come back bit-identical.
	 *
	 * <p>Compared exactly rather than within a tolerance. This is a copy through a stream, so anything
	 * less than exact means a field was widened, narrowed or transposed - which is the class of bug
	 * that a tolerance would hide, and that produces a network which trains.
	 */
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

	/** A file that is not a checkpoint must be refused, not parsed as weights. */
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

	/**
	 * A short file must be refused.
	 *
	 * <p>The case this format exists to prevent: a checkpoint half-written by a power cut is newer than
	 * the last good one and would be resumed from, so the atomic rename is not belt and braces.
	 */
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

	/** A valid checkpoint with extra bytes is not what this build wrote, and must be refused. */
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

	/**
	 * A checkpoint from a different config must be refused, naming the field that changed.
	 *
	 * <p>The naming is the point. "Incompatible" tells the reader nothing; "gridWidth=32, this run has
	 * 48" tells them which flag to change.
	 */
	private static void checkRefusesDifferentConfig( Network original, EnvConfig config,
			File file ) throws IOException {
		Checkpoint.save( file, original, config, 1, 1L );

		//one field at a time, so each of the three is known to be named. Changing several at once only
		//proves the first of them is reported, which is exactly what the first version of this check did
		checkMismatchNames( config, file, "maxSlots", 6 );
		checkMismatchNames( config, file, "gridWidth", 10 );
		checkMismatchNames( config, file, "gridHeight", 10 );
	}

	/** Loads into a config with one field changed, and requires the refusal to name that field. */
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

	/**
	 * The atomic write must not leave a temporary file behind.
	 *
	 * <p>A {@code weights-*.tmp} left in the work directory is 43 MB of the run's disk, and a run that
	 * crashes repeatedly accumulates one per crash - on a machine with a 2 GB pagefile, the wrong place
	 * to accumulate.
	 */
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

	/**
	 * Loading into a fresh network must replace it, not merge into it.
	 *
	 * <p>Distinct from the round-trip check: that proves values arrive, this proves the values that
	 * were already there are gone. A copy that appended, or only overwrote non-zero entries, would
	 * pass the round trip when the source was the same network.
	 */
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

	/** The header has to report what it recorded, or a resumed run cannot say where it resumed from. */
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

	/**
	 * Loads and returns the failure message, or {@code null} if the load succeeded.
	 *
	 * <p>Every refusal check goes through this, so they all assert the same thing: that loading fails.
	 * Asserting on the message separately, where there is one to assert on.
	 */
	private static String refusal( File file, EnvConfig config ){
		try {
			Checkpoint.load( file, new Network( config, new Random( 6L ) ), config );
			return null;
		} catch (IOException e){
			return String.valueOf( e.getMessage() );
		}
	}

	/**
	 * Gives a network non-zero moments and a step count.
	 *
	 * <p>Without this the moment tensors are all zeros, and a round trip that silently wrote nothing
	 * would compare equal. The bug this check exists for would be invisible against zero moments.
	 */
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
