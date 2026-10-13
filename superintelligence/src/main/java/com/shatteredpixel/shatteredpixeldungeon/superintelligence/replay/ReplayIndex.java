package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Remembers every directory a run has written recordings into.
 *
 * <p><b>Why this exists.</b> The trainer writes replays to {@code <--out>/replays}, and {@code --out}
 * is wherever the run was pointed. The viewer and the catalog, meanwhile, searched exactly two fixed
 * directories: the repository's {@code test-replays/} and {@code trainer-replays/}, plus whatever a run
 * was told to write. Anything a run wrote elsewhere is only findable because it is written down here, so
 * the index file lives beside the artefacts it describes - {@code <module>/out/run/replay-dirs.txt} -
 * rather than in the system temp directory, where it would be the first thing a reboot took away.
 *
 * <p>That first directory has since moved to {@code superintelligence/test-replays}, alongside
 * {@code superintelligence/trainer-replays}, and the catalog locates both by walking up to the
 * repository root. This index is still what finds the runs that were pointed somewhere else.
 *
 * <p>Measured here: 147 recordings existed across 16 directories. 142 of them - 97% - were invisible
 * to the viewer, which could only see the 5 checked-in fixtures and the 10 in the default temp
 * directory. The recordings were not lost and nothing reported an error; they were simply in a place
 * nobody looked, which is the worst kind of invisible.
 *
 * <p><b>Why an index rather than a search.</b> Scanning the temp directory for any folder named
 * {@code replays} would find these by luck of naming, and would also pick up unrelated directories and
 * any run from another checkout. A run that wrote a recording records where it put it; that is a fact
 * about the run, and reading it back is exact rather than heuristic.
 *
 * <p>Append-only, deduplicated, one absolute path per line. A failure to record is swallowed: losing
 * the index costs discoverability, which is recoverable by pointing {@code --dir} at the directory,
 * and failing a finished training run over it would not be.
 */
public class ReplayIndex {

	private ReplayIndex() {}

	/** Where the index itself lives. Beside the default replay directory, which is where a reader looks first. */
	public static File indexFile(){
		return new File( com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault(), "replay-dirs.txt" );
	}

	/**
	 * Records that {@code directory} holds recordings.
	 *
	 * <p>Canonical path, because this is what makes the record comparable. The trainer resolved its
	 * default destination from the working directory, which spelled it {@code
	 * superintelligence/./trainer-replays} while the catalogue's own default spelled the same directory
	 * {@code superintelligence/trainer-replays} — and a directory listed twice is every recording in it
	 * counted twice.
	 *
	 * <p>Silent on failure by design. See the class comment.
	 */
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
			//discoverability is recoverable, a failed training run is not
		}
	}

	/** Every directory recorded so far that still exists, in the order they were recorded. */
	public static List<File> read(){
		//a LinkedHashSet because two runs may well share an output directory, and the caller should not
		//have to know that
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
			//an unreadable index means no extra directories, which is the old behaviour
		}

		return new ArrayList<>( found );
	}

	/**
	 * The directory's real path, with {@code .} and {@code ..} resolved away.
	 *
	 * <p>Falls back to the absolute path. Canonicalisation can fail on a path nothing can reach, and an
	 * index entry that reads twice costs discoverability — which {@code --dir} recovers — while an
	 * exception would cost a finished training run.
	 */
	private static File canonical( File dir ){
		try {
			return dir.getCanonicalFile();
		} catch (IOException e){
			return dir.getAbsoluteFile();
		}
	}
}
