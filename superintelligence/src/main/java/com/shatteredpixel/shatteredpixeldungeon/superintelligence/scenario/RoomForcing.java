package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultEntranceRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultFinalRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultSimpleEnemyTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultTokensRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultTreasureRoom;
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

	static Runnable vault( Class< ? extends Room > type ){
		return () -> {
			vaultReset().run();
			if (VaultTreasureRoom.class.isAssignableFrom( type )){
				treasure( type );
			} else if (VaultRoom.class.isAssignableFrom( type ) && !alwaysBuilt( type )){
				rooms( type );
			}
		};
	}

	static Runnable vaultReset(){
		return () -> {
			replace( VaultRoom.class, "rooms", VAULT_ROOMS );
			replace( VaultTreasureRoom.class, "T1_ROOMS", VAULT_T1 );
			replace( VaultTreasureRoom.class, "T2_ROOMS", VAULT_T2 );
			replace( VaultTreasureRoom.class, "T3_ROOMS", VAULT_T3 );
		};
	}

	private static final List< Object > VAULT_ROOMS = snapshot( VaultRoom.class, "rooms" );

	private static final List< Object > VAULT_T1 = snapshot( VaultTreasureRoom.class, "T1_ROOMS" );

	private static final List< Object > VAULT_T2 = snapshot( VaultTreasureRoom.class, "T2_ROOMS" );

	private static final List< Object > VAULT_T3 = snapshot( VaultTreasureRoom.class, "T3_ROOMS" );

	@SuppressWarnings("unchecked")
	private static List< Object > snapshot( Class< ? > owner, String name ){
		try {
			Field field = owner.getDeclaredField( name );
			field.setAccessible( true );
			return new ArrayList<>( (List< Object >) field.get( null ));
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( owner.getSimpleName() + "." + name + " is unreadable", e );
		}
	}

	@SuppressWarnings("unchecked")
	private static List< Object > rooms( Class< ? > owner, String name ){
		try {
			Field field = owner.getDeclaredField( name );
			field.setAccessible( true );
			return (List< Object >) field.get( null );
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( owner.getSimpleName() + "." + name + " is unreadable", e );
		}
	}

	private static void replace( Class< ? > owner, String name, List< Object > original ){
		List< Object > rooms = rooms( owner, name );
		rooms.clear();
		rooms.addAll( original );
	}

	private static boolean alwaysBuilt( Class< ? > type ){
		return type == VaultEntranceRoom.class || type == VaultFinalRoom.class
				|| type == VaultTokensRoom.class || type == VaultSimpleEnemyTreasureRoom.class;
	}

	private static void rooms( Class< ? > type ){
		List< Object > rooms = rooms( VaultRoom.class, "rooms" );
		List< Object > queued = new ArrayList<>( rooms );
		rooms.clear();
		for (int i = 0; i < COPIES; i++) rooms.add( type );
		for (Object room : queued) if (room != type) rooms.add( room );
	}

	private static void treasure( Class< ? > type ){
		for (String tier : new String[]{ "T1_ROOMS", "T2_ROOMS", "T3_ROOMS" }){
			List< Object > rooms = rooms( VaultTreasureRoom.class, tier );
			if (rooms.remove( type )){
				rooms.add( 0, type );
				return;
			}
		}
		throw new IllegalStateException( type.getSimpleName() + " belongs to no vault treasure tier" );
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
