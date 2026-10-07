package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A trained policy on disk, so a run can be stopped, inspected, and continued.
 *
 * <p>Until this existed, {@link Trainer} started from {@code Network}'s random initialisation every
 * time and threw the result away at exit. Every run so far was therefore a smoke test that could not
 * be extended: no way to run 50 generations and continue, and no way to compare two runs, because only
 * one line of training could exist at a time.
 *
 * <p><b>What a checkpoint has to contain, and what it deliberately does not.</b> Weights are obvious.
 * The Adam moments and the optimiser step count are the parts that are easy to omit and turn a resume
 * into a different run:
 *
 * <ul>
 *   <li><b>Moments.</b> Dropping them restarts Adam's averages from zero, so the first update after a
 *       resume divides by a near-empty gradient mean. It would still train, and it would train worse,
 *       with nothing in the output to say why.
 *   <li><b>Step count.</b> Adam's bias correction divides by {@code 1 - beta^step}, so restarting at 1
 *       makes that correction ~0.1 instead of ~1 — a full-size step on a barely-warmed average.
 * </ul>
 *
 * <p>Adam moments are therefore saved but <b>not</b> sent to workers. A worker runs forward passes
 * only; pushing four times the floats per generation would cost ~57 MB per worker per push for nothing.
 *
 * <p><b>The config is written and checked</b> rather than assumed. A checkpoint from a different
 * {@link EnvConfig} has different tensor shapes, and loading it into the wrong parameters produces a
 * network that runs and learns nonsense. Every shape-affecting field is stored and compared, with the
 * offending name in the message — which is the difference between "IllegalArgumentException" and
 * "your grid is 48x48, this checkpoint is 32x32".
 *
 * <p><b>Written to a temporary file and renamed.</b> A checkpoint half-written by a power cut is worse
 * than none: it exists, it is newer, and resuming from it fails on a short read. The rename is atomic
 * on every filesystem worth training on, so the file at the target path is always a complete previous
 * checkpoint or a complete new one.
 */
public final class Checkpoint {

	/** File magic and format version, so a truncated or foreign file is refused rather than parsed. */
	private static final int MAGIC = 0x53504431; // "SPD1"
	private static final int FORMAT = 1;

	private Checkpoint() {}

	/** What a resumed run needs to know about where it left off, for logging and the CSV. */
	public static class Meta {
		public int generation;
		public int adamSteps;
		public long trainSeed;

		public int maxSlots;
		public int gridWidth;
		public int gridHeight;

		@Override public String toString(){
			return "generation " + generation + ", adam step " + adamSteps
					+ ", train seed " + trainSeed + ", grid " + gridWidth + "x" + gridHeight
					+ ", " + maxSlots + " slots";
		}
	}

	/**
	 * Writes the policy, its optimiser state, and the config that produced it.
	 *
	 * @param file      destination; replaced atomically
	 * @param network   the policy to save
	 * @param config    the config whose shape the weights depend on
	 * @param generation progress marker, so a resumed run can say where it resumed from
	 * @param trainSeed the trainer's seed, recorded for provenance; it is not restored
	 */
	public static void save( File file, Network network, EnvConfig config,
			int generation, long trainSeed ) throws IOException {

		Path target = file.toPath();
		Path parent = target.getParent();
		if (parent != null) Files.createDirectories( parent );

		//a sibling temp file, so the rename cannot cross a filesystem boundary and lose atomicity
		Path temp = Files.createTempFile( parent, "weights-", ".tmp" );
		try {
			try (DataOutputStream out = new DataOutputStream(
					new BufferedOutputStream( Files.newOutputStream( temp ) ))){
				out.writeInt( MAGIC );
				out.writeInt( FORMAT );

				out.writeInt( generation );
				out.writeInt( network.adamSteps() );
				out.writeLong( trainSeed );

				//the config fields the tensor shapes depend on. Not the reward weights or the turn
				//limits: those change what a run scores without changing what it can represent, and
				//refusing to resume across a reward tweak would be a nuisance rather than a safeguard
				out.writeInt( config.maxSlots );
				out.writeInt( config.gridWidth );
				out.writeInt( config.gridHeight );

				Network.Layer[] layers = network.layers();
				out.writeInt( layers.length );
				for (Network.Layer layer : layers){
					out.writeUTF( layer.name );
					out.writeInt( layer.in );
					out.writeInt( layer.out );
					writeFloats( out, layer.weights );
					writeFloats( out, layer.bias );
				}

				Network.Moments[] moments = network.moments();
				out.writeInt( moments.length );
				for (Network.Moments m : moments){
					out.writeUTF( m.name );
					writeFloats( out, m.mW );
					writeFloats( out, m.vW );
					writeFloats( out, m.mb );
					writeFloats( out, m.vb );
				}
			}

			Files.move( temp, target, StandardCopyOption.REPLACE_EXISTING );
		} catch (IOException | RuntimeException e){
			Files.deleteIfExists( temp );
			throw e;
		}
	}

	/**
	 * Reads a checkpoint into {@code network}.
	 *
	 * @throws IOException on anything that would make the resumed run a different run: a foreign or
	 *                     truncated file, a config mismatch, or a tensor of the wrong length. Never
	 *                     partially — the network is only mutated once every layer has been validated.
	 */
	public static Meta load( File file, Network network, EnvConfig config ) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream( Files.newInputStream( file.toPath() ) ))){

			int magic = in.readInt();
			if (magic != MAGIC){
				throw new IOException( file + " is not a policy checkpoint"
						+ " (bad magic 0x" + Integer.toHexString( magic ) + "). Refusing to load it:"
						+ " a truncated file would otherwise resume from partial weights." );
			}

			int format = in.readInt();
			if (format != FORMAT){
				throw new IOException( file + " is checkpoint format " + format
						+ " and this build reads format " + FORMAT + ". Refusing rather than"
						+ " misreading it." );
			}

			Meta meta = new Meta();
			meta.generation = in.readInt();
			meta.adamSteps = in.readInt();
			meta.trainSeed = in.readLong();
			meta.maxSlots = in.readInt();
			meta.gridWidth = in.readInt();
			meta.gridHeight = in.readInt();

			checkConfig( file, meta, config );

			int layerCount = in.readInt();
			Network.Layer[] layers = new Network.Layer[ layerCount ];
			for (int i = 0; i < layerCount; i++){
				String name = in.readUTF();
				int in2 = in.readInt();
				int out2 = in.readInt();
				layers[ i ] = new Network.Layer( name, in2, out2,
						readFloats( in ), readFloats( in ) );
			}

			//read before writing anything, so a truncated file cannot leave a half-loaded network
			int momentGroups = in.readInt();
			Network.Moments[] moments = new Network.Moments[ momentGroups ];
			for (int i = 0; i < momentGroups; i++){
				String name = in.readUTF();
				moments[ i ] = new Network.Moments( name,
						readFloats( in ), readFloats( in ), readFloats( in ), readFloats( in ) );
			}

			if (in.available() > 0){
				throw new IOException( file + " has " + in.available()
						+ " bytes left after " + momentGroups + " moment groups, so it is not a"
						+ " checkpoint this build wrote." );
			}

			for (Network.Layer layer : layers) network.loadLayer( layer );
			for (Network.Moments m : moments) network.loadMoments( m );

			//last, because it is the one thing that is not a tensor and so cannot be validated above
			network.adamSteps( meta.adamSteps );

			return meta;
		} catch (java.io.EOFException e){
			throw new IOException( file + " is truncated. A checkpoint is written to a temporary"
					+ " file and renamed, so this should not happen — the file is probably from a"
					+ " build that predates atomic writes.", e );
		}
	}

	/**
	 * Refuses a checkpoint whose shapes cannot belong to this config.
	 *
	 * <p>Reported per field rather than as one "incompatible" so the fix is obvious. Loading anyway
	 * would be caught by {@link Network#loadLayer}'s shape check for the tensors, but only after the
	 * moments had already been read, and the message would be about a layer rather than about the
	 * setting the user actually changed.
	 */
	private static void checkConfig( File file, Meta meta, EnvConfig config ) throws IOException {
		mismatch( file, "maxSlots", meta.maxSlots, config.maxSlots );
		mismatch( file, "gridWidth", meta.gridWidth, config.gridWidth );
		mismatch( file, "gridHeight", meta.gridHeight, config.gridHeight );
	}

	private static void mismatch( File file, String field, int was, int now ) throws IOException {
		if (was == now) return;
		throw new IOException( file + " was trained with " + field + "=" + was
				+ " and this run has " + field + "=" + now
				+ ". The observation encoding depends on it, so the weights do not fit."
				+ " Resume with the original setting, or start a new run." );
	}

	private static void writeFloats( DataOutputStream out, float[] values ) throws IOException {
		out.writeInt( values.length );
		for (float v : values) out.writeFloat( v );
	}

	private static float[] readFloats( DataInputStream in ) throws IOException {
		int count = in.readInt();
		if (count < 0) throw new IOException( "a tensor in the checkpoint claims " + count + " floats" );
		//a length is the one field that can be hostile, so it is bounded against what this build could
		//possibly hold rather than trusted: the largest tensor here is the CNN trunk at a few million
		if (count > 64 * 1024 * 1024){
			throw new IOException( "a tensor in the checkpoint claims " + count
					+ " floats, which is more than any layer in this network has" );
		}
		float[] values = new float[ count ];
		for (int i = 0; i < count; i++) values[ i ] = in.readFloat();
		return values;
	}
}
