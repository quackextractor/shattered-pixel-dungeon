package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ReplayCatalogFixture {

	static final List< String > failures = new ArrayList<>();

	static File workDir;

	private ReplayCatalogFixture(){}

	static File dir( String name ){
		File dir = new File( workDir, name );
		if (!dir.mkdirs() && !dir.isDirectory()){
			throw new IllegalStateException( "could not create " + dir );
		}
		return dir;
	}

	static File write( File dir, String seed, String hero, double score, int depth, int turns ){
		return write( dir, seed, hero, score, depth, turns, 1, 1 );
	}

	static File write( File dir, String seed, String hero, double score,
			int depth, int turns, int declared, int actual ){
		return writeFile( new File( dir, seed + ".replay" ), seed, "", hero,
				score, depth, turns, declared, actual );
	}

	static File writeAs( File dir, String seed, String fileStem, String hero,
			double score, int depth, int turns ){
		return writeFile( new File( dir, fileStem + ".replay" ), seed, "", hero, score, depth, turns, 1, 1 );
	}

	static File writeGrouped( File dir, String group, String seed, String hero,
			double score, int depth, int turns ){
		return writeGrouped( dir, group, seed, hero, score, depth, turns, 1, 1 );
	}

	static File writeGrouped( File dir, String group, String seed, String hero,
			double score, int depth, int turns, int declared, int actual ){
		File groupDir = new File( dir, group );
		if (!groupDir.mkdirs() && !groupDir.isDirectory()){
			throw new IllegalStateException( "could not create " + groupDir );
		}
		return writeFile( new File( groupDir, seed + ".replay" ), seed, group, hero,
				score, depth, turns, declared, actual );
	}

	private static File writeFile( File file, String seed, String group, String hero, double score,
			int depth, int turns, int declared, int actual ){

		List<String> lines = new ArrayList<>( Arrays.asList(
				Replay.MAGIC,
				"version=" + Replay.VERSION,
				"seed=" + seed,
				"hero=" + hero,
				"challenges=0",
				"generation=0",
				"score=" + score,
				"depth=" + depth,
				"turns=" + turns,
				"turn_limit=" + turns,
				"max_slots=32",
				"allow_equipping=true",
				"termination=OTHER",
				"steps=" + declared ));

		if (!group.isEmpty()) lines.add( "group=" + group );
		for (int i = 0; i < actual; i++) lines.add( "WAIT 0 WORLD 100 0.0  20 " + i + ".0 Food:1" );

		try {
			Files.write( file.toPath(), lines, StandardCharsets.UTF_8 );
		} catch (IOException e){
			throw new IllegalStateException( "could not write " + file, e );
		}
		return file;
	}

	static Replay readQuietly( File file ){
		try {
			return ReplayIO.read( file );
		} catch (IOException e){
			fail( "could not read the fixture just written: " + e.getMessage() );
			return new Replay();
		}
	}

	static void writeBack( File file, Replay replay ){
		try {
			ReplayIO.write( replay, file );
		} catch (IOException e){
			fail( "could not write back the fixture: " + e.getMessage() );
		}
	}

	static void dropEveryStepField( Replay replay ){
		for (Replay.Step step : replay.steps){
			step.quickslots = "";
			step.heroHp = -1;
			step.turn = -1f;
			step.inventory = "";
		}
	}

	static String rowFor( String listing, String label ){
		for (String line : listing.split( "\n" )){
			if (line.contains( label )) return line;
		}
		return "";
	}

	static int depthColumnIn( String row ){
		String[] cells = row.trim().split( "\\s+" );
		if (cells.length < 4) return -1;
		String cell = cells[ 3 ];
		return cell.matches( "d\\d+" ) ? row.indexOf( cell ) : -1;
	}

	static List< ReplayCatalog.Entry > flatten( Map< String, List< ReplayCatalog.Entry > > groups ){
		List< ReplayCatalog.Entry > all = new ArrayList<>();
		for (List< ReplayCatalog.Entry > list : groups.values()) all.addAll( list );
		return all;
	}

	static void deleteTree( File file ){
		File[] children = file.listFiles();
		if (children != null) for (File child : children) deleteTree( child );
		if (!file.delete()) file.deleteOnExit();
	}

	static void fail( String message ){
		failures.add( message );
	}
}