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

public final class Checkpoint {

	private static final int MAGIC = 0x53504431; 
	private static final int FORMAT = 1;

	private Checkpoint() {}

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

	public static void save( File file, Network network, EnvConfig config,
			int generation, long trainSeed ) throws IOException {

		Path target = file.toPath();
		Path parent = target.getParent();
		if (parent != null) Files.createDirectories( parent );

		Path temp = Files.createTempFile( parent, "weights-", ".tmp" );
		try {
			try (DataOutputStream out = new DataOutputStream(
					new BufferedOutputStream( Files.newOutputStream( temp ) ))){
				out.writeInt( MAGIC );
				out.writeInt( FORMAT );

				out.writeInt( generation );
				out.writeInt( network.adamSteps() );
				out.writeLong( trainSeed );

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

			network.adamSteps( meta.adamSteps );

			return meta;
		} catch (java.io.EOFException e){
			throw new IOException( file + " is truncated. A checkpoint is written to a temporary"
					+ " file and renamed, so this should not happen — the file is probably from a"
					+ " build that predates atomic writes.", e );
		}
	}

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

		if (count > 64 * 1024 * 1024){
			throw new IOException( "a tensor in the checkpoint claims " + count
					+ " floats, which is more than any layer in this network has" );
		}
		float[] values = new float[ count ];
		for (int i = 0; i < count; i++) values[ i ] = in.readFloat();
		return values;
	}
}
