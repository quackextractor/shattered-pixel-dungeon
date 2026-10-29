package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Scratch;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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

		if (workDir == null){
			workDir = Scratch.dir( "worker" );
		}
		if (!workDir.isDirectory() && !workDir.mkdirs()){
			System.err.println( "[ERROR] worker: could not create " + workDir );
			System.exit( 1 );
		}

		try {
			serveStreams( System.in, System.out, workDir );
			System.exit( 0 );
		} catch (IOException e){
			System.err.println( "[ERROR] worker: " + e );
			System.exit( 1 );
		}
	}

	public static void serveStreams( InputStream in, OutputStream out, File workDir ) throws IOException {
		if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException( "could not create " + workDir );
		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( true );
		try {
			new Worker( new DataInputStream( new BufferedInputStream( in ) ),
					new DataOutputStream( new BufferedOutputStream( out ) ) ).serve();
		} catch (EOFException e){
			return;
		}
	}
}