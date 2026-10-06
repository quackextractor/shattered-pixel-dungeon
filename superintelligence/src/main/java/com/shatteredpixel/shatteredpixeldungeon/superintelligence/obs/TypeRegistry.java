package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable integer identities for item classes.
 *
 * The observation encodes each slot's item as an index into a learned embedding rather than a
 * one-hot over every item class. One-hot would need a slot feature vector wider than the entire
 * item tree - several hundred entries - and would have to be rebuilt whenever the game adds an
 * item, invalidating every checkpoint.
 *
 * Indices are assigned on first sight and never reassigned, so a checkpoint keeps working across
 * a patch that introduces new items. New items simply take new indices with untrained embeddings,
 * which the policy will avoid until they earn reward, and which is the correct prior.
 *
 * The map is per-process. Workers are separate JVMs and assign indices in whatever order they
 * happen to meet item classes, so indices are not comparable across workers. That is fine: the
 * trainer holds one policy and never inspects the raw index, only the embedding it learned.
 */
public class TypeRegistry {

	private static final Map<Class<?>, Integer> indices = new HashMap<>();
	private static final List<Class<?>> classes = new ArrayList<>();

	private TypeRegistry() {}

	/** Index for {@code type}, assigning a new one if this is the first time it is seen. */
	public static int indexOf( Class<?> type ){
		Integer existing = indices.get( type );
		if (existing != null) return existing;

		int index = classes.size();
		classes.add( type );
		indices.put( type, index );
		return index;
	}

	/** Number of distinct indices assigned so far. */
	public static int size(){
		return classes.size();
	}

	/** The class an index refers to, for diagnostics. */
	public static Class<?> classAt( int index ){
		return (index >= 0 && index < classes.size()) ? classes.get( index ) : null;
	}
}