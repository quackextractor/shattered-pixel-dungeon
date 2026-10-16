package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TypeRegistry {

	private static final Map<Class<?>, Integer> indices = new HashMap<>();
	private static final List<Class<?>> classes = new ArrayList<>();

	private TypeRegistry() {}

	public static int indexOf( Class<?> type ){
		Integer existing = indices.get( type );
		if (existing != null) return existing;

		int index = classes.size();
		classes.add( type );
		indices.put( type, index );
		return index;
	}

	public static int size(){
		return classes.size();
	}

	public static Class<?> classAt( int index ){
		return (index >= 0 && index < classes.size()) ? classes.get( index ) : null;
	}
}