package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;

/**
 * Entry point for a worker process.
 *
 * The worker installs the headless services into its own save directory, then serves the trainer
 * over stdin and stdout. Nothing is written to stdout except protocol frames, so a stray print
 * anywhere in the game logic cannot desynchronise the stream.
 */
public class WorkerMain {

	public static void main( String[] args ){
		if (args.length > 0 && args[ 0 ].equals( "--worker" )){
			File workDir = null;
			for (int i = 1; i < args.length; i++){
				if (args[ i ].equals( "--work-dir" ) && i + 1 < args.length){
					workDir = new File( args[ ++i ] );
				}
			}
			serve( workDir );
		} else {
			System.err.println( "usage: WorkerMain --worker [--work-dir <dir>]" );
			System.exit( 1 );
		}
	}

	private static void serve( File workDir ){
		//Per-worker save directory. Sharing one across the pool made two workers race on the same
		//temporary file, which the engine reports as a save failure even though saving is disabled
		//for training - the game still creates the directory tree on its way to deciding it will not
		//write.
		if (workDir == null){
			workDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-worker" );
		}
		if (!workDir.isDirectory() && !workDir.mkdirs()){
			System.err.println( "[ERROR] worker: could not create " + workDir );
			System.exit( 1 );
		}

		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( true );

		DataInputStream in = new DataInputStream( new BufferedInputStream( System.in ) );
		DataOutputStream out = new DataOutputStream( new BufferedOutputStream( System.out ) );

		try {
			new Worker( in, out ).serve();
		} catch (EOFException e){
			//the trainer closed the pipe. That is how a run ends, not a failure.
			System.exit( 0 );
		} catch (IOException e){
			System.err.println( "[ERROR] worker: " + e );
			System.exit( 1 );
		}
	}
}