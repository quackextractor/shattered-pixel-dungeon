package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

public final class Scratch {

	private Scratch(){}

	public static File dir( String gate ){
		File dir;
		try {
			dir = Files.createTempDirectory( "spd-" + gate + "-" ).toFile();
		} catch (IOException e){
			throw new UncheckedIOException( e );
		}
		Runtime.getRuntime().addShutdownHook( new Thread( () -> delete( dir ) ) );
		return dir;
	}

	private static void delete( File file ){
		File[] children = file.listFiles();
		if (children != null){
			for (File child : children) delete( child );
		}
		file.delete();
	}
}
