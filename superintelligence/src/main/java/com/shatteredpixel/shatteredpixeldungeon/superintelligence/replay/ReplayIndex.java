package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ReplayIndex {

	private ReplayIndex() {}

	private static volatile File override;

	public static void useIndexFile( File file ){
		override = file;
	}

	public static File indexFile(){
		if (override != null) return override;
		return new File( com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault(), "replay-dirs.txt" );
	}

	public static void record( File directory ){
		if (directory == null) return;

		File absolute = canonical( directory );
		if (!absolute.isDirectory()) return;

		for (File known : read()){
			if (known.equals( absolute )) return;
		}

		File index = indexFile();
		try {
			File parent = index.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;

			Files.write( index.toPath(),
					( absolute.getPath() + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND );
		} catch (IOException ignored){

		}
	}

	public static List<File> read(){

		LinkedHashSet<File> found = new LinkedHashSet<>();

		File index = indexFile();
		if (!index.isFile()) return new ArrayList<>( found );

		try {
			for (String line : Files.readAllLines( index.toPath(), StandardCharsets.UTF_8 )){
				String path = line.trim();
				if (path.isEmpty()) continue;

				File dir = canonical( new File( path ));
				if (dir.isDirectory()) found.add( dir );
			}
		} catch (IOException ignored){

		}

		return new ArrayList<>( found );
	}

	private static File canonical( File dir ){
		try {
			return dir.getCanonicalFile();
		} catch (IOException e){
			return dir.getAbsoluteFile();
		}
	}
}
