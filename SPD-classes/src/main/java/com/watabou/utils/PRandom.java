package com.watabou.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Randomness for presentation only: particles, emotes, music selection, colour jitter and the pitch of
 * a sound effect.
 *
 * <p>None of it can change a game outcome, but drawing it from {@link Random} meant it consumed the same
 * stream the simulation runs on. That is invisible in a normal playthrough - there is only one
 * environment - and it makes headless rollouts impossible to reconcile with the game: every particle
 * effect the renderer ran advanced the stream by a different amount than a headless run could, so the
 * two diverged permanently from the first floor and no recording made headlessly could ever replay.
 *
 * <p>The two faults found this way were an observation encoder calling {@code Hero.drRoll} to read a
 * property of the hero, and the particle system's emission delay. Both were symptoms of the same
 * mistake: using the gameplay stream for something that is not gameplay.
 *
 * <p>This is a separate generator rather than a flag on {@link Random}, so no presentation code has to
 * remember to scope itself and no gameplay code has to be audited. It is reseeded with the run seed, so
 * a run is as reproducible as it was - the values simply come from their own stream.
 */
public class PRandom {

	private static java.util.Random generator = new java.util.Random( 0x9E3779B97F4A7C15L );

	/** Called from {@link Random#reseedBase} so both streams follow the run seed. */
	public static synchronized void reseed( long seed ){
		generator = new java.util.Random( seed ^ 0x5DEECE66DL );
	}

	public static synchronized float Float(){
		return generator.nextFloat();
	}

	public static synchronized float Float( float max ){
		return Float( 0, max );
	}

	public static synchronized float Float( float min, float max ){
		return min + generator.nextFloat() * (max - min);
	}

	public static synchronized float NormalFloat( float min, float max ){
		return min + ((Float( max - min ) + Float( max - min )) / 2f);
	}

	public static synchronized int Int(){
		return generator.nextInt();
	}

	public static synchronized int Int( int max ){
		return generator.nextInt( max );
	}

	public static synchronized int IntRange( int min, int max ){
		return min + generator.nextInt( max - min );
	}

	public static synchronized int NormalIntRange( int min, int max ){
		return min + generator.nextInt( max - min );
	}

	public static synchronized boolean Boolean(){
		return generator.nextBoolean();
	}

	/**
	 * A random element of a collection, or null when it is empty.
	 *
	 * <p>Mirrors {@code Random.element} so that a caller choosing presentation can be moved across
	 * without having to reimplement the indexing. {@code AttackIndicator} picks the mob its overlay
	 * highlights, which is why it is here rather than on the gameplay stream: the highlight is a UI
	 * decision, and spending a gameplay value on it shifted every later roll in the rendered viewer.
	 */
	@SuppressWarnings("unchecked")
	public static synchronized < T > T element( java.util.Collection<? extends T > collection ){
		int size = collection.size();
		return size > 0 ? (T) collection.toArray()[ Int( size ) ] : null;
	}

	public static synchronized < T > void shuffle( List< T > list ){
		Collections.shuffle( list, generator );
	}

	public static synchronized < T > void shuffle( T[] array ){
		List< T > list = new ArrayList<>( java.util.Arrays.asList( array ) );
		Collections.shuffle( list, generator );
		for (int i = 0; i < array.length; i++) array[ i ] = list.get( i );
	}

	private PRandom() {}
}