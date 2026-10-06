package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
			serve();
		} else {
			System.err.println( "usage: WorkerMain --worker" );
			System.exit( 1 );
		}
	}

	private static void serve(){
		//per-worker save directory, so workers never interleave writes to one save file
		File workDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-worker" );
		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( true );

		DataInputStream in = new DataInputStream( new BufferedInputStream( System.in ) );
		DataOutputStream out = new DataOutputStream( new BufferedOutputStream( System.out ) );

		try {
			new Worker( in, out ).serve();
		} catch (IOException e){
			System.err.println( "[ERROR] worker: " + e.getMessage() );
			System.exit( 1 );
		}
	}
}