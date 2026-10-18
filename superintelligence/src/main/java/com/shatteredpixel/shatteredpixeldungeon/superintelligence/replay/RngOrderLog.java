package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.watabou.utils.RandomTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class RngOrderLog {

	public static final String ORDER_SUFFIX = ".order.txt";
	public static final String PUSHES_SUFFIX = ".pushes.txt";

	private RngOrderLog() {}

	public static void writeCurrent( String pathPrefix ) throws IOException {
		write( pathPrefix, RandomTrace.siteLog(), RandomTrace.generatorLog() );
	}

	public static void write( String pathPrefix, List< String > order, List< String > pushes )
			throws IOException {
		writeLines( pathPrefix + ORDER_SUFFIX, order );
		writeLines( pathPrefix + PUSHES_SUFFIX, pushes );
	}

	private static void writeLines( String path, List< String > lines ) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++){
			sb.append( i ).append( '\t' ).append( lines.get( i ) ).append( '\n' );
		}
		Files.write( Paths.get( path ), sb.toString().getBytes( StandardCharsets.UTF_8 ));
	}
}