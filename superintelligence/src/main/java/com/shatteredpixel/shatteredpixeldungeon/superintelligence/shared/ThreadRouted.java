package com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadRouted {

	private static final Map< Class< ? >, ThreadLocal< Object > > CURRENT = new ConcurrentHashMap<>();
	private static final Map< Class< ? >, Object > FALLBACK = new ConcurrentHashMap<>();
	private static final Map< Class< ? >, Object > PROXIES = new ConcurrentHashMap<>();

	private ThreadRouted(){}

	public static < T > T route( Class< T > type, T service ){
		CURRENT.computeIfAbsent( type, t -> new ThreadLocal<>() ).set( service );
		FALLBACK.put( type, service );
		return type.cast( PROXIES.computeIfAbsent( type, t -> Proxy.newProxyInstance( ThreadRouted.class.getClassLoader(),
				new Class< ? >[]{ t }, ( proxy, method, args ) -> {
					Object target = CURRENT.get( t ).get();
					if (target == null) target = FALLBACK.get( t );
					try {
						return method.invoke( target, args );
					} catch (InvocationTargetException e){
						throw e.getCause();
					}
				} )));
	}
}
