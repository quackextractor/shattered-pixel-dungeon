package com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared;

public final class InferenceBroker {

	public interface Server {
		int open();
		void reset( int slot );
		void save( int slot, float[] into );
		void load( int slot, float[] from );
		void forward( int slot, byte[] packedGrid, float[] inventory, float[] hero, float[] out );
	}

	private static volatile Server server;

	private InferenceBroker(){}

	public static void install( Server s ){
		server = s;
	}

	public static Server server(){
		return server;
	}
}
