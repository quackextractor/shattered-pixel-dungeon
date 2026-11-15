package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RoomForcing {

	private RoomForcing(){}

	private static final int COPIES = 3;

	private static final int SECRETS_PER_REGION = 4;

	static Runnable special( Class< ? extends SpecialRoom > type ){
		return () -> SpecialRoom.runSpecials = front( type, SpecialRoom.runSpecials );
	}

	static Runnable secret( Class< ? extends SecretRoom > type ){
		return () -> {
			SecretRoom.runSecrets = front( type, SecretRoom.runSecrets );
			int[] regions = regionSecrets();
			for (int i = 0; i < regions.length; i++) regions[ i ] = Math.max( regions[ i ], SECRETS_PER_REGION );
		};
	}

	private static < T > ArrayList< T > front( T type, List< T > queue ){
		ArrayList< T > out = new ArrayList<>( Collections.nCopies( COPIES, type ));
		for (T queued : queue) if (queued != type) out.add( queued );
		return out;
	}

	private static int[] regionSecrets(){
		try {
			Field field = SecretRoom.class.getDeclaredField( "regionSecretsThisRun" );
			field.setAccessible( true );
			return (int[]) field.get( null );
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "SecretRoom.regionSecretsThisRun is unreadable", e );
		}
	}
}
