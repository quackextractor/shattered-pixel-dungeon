package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessScheduler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SchedulerStepScan {

	private static final String STEP = "headlessStep";

	private static final Set< String > ALLOWED = Set.of( HeadlessScheduler.class.getName(), SchedulerStepScan.class.getName() );

	private SchedulerStepScan(){}

	public static List< String > directCallers( Class< ? >... anchors ){
		List< String > callers = new ArrayList<>();
		for (Class< ? > anchor : anchors){
			RenderDrawCheck.forEachClass( anchor, ( name, bytes ) -> {
				if (!ALLOWED.contains( name ) && utf8Entries( bytes ).contains( STEP )) callers.add( name );
			});
		}
		return callers;
	}

	static Set< String > utf8Entries( byte[] bytes ) throws IOException {
		DataInputStream in = new DataInputStream( new ByteArrayInputStream( bytes ));
		in.readInt();
		in.readUnsignedShort();
		in.readUnsignedShort();
		int count = in.readUnsignedShort();
		Set< String > entries = new HashSet<>();
		for (int i = 1; i < count; i++){
			int tag = in.readUnsignedByte();
			switch (tag){
				case 1: entries.add( in.readUTF() ); break;
				case 7: case 8: case 16: case 19: case 20: in.skipBytes( 2 ); break;
				case 15: in.skipBytes( 3 ); break;
				case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes( 4 ); break;
				case 5: case 6: in.skipBytes( 8 ); i++; break;
				default: throw new IOException( "unknown constant pool tag " + tag );
			}
		}
		return entries;
	}
}
