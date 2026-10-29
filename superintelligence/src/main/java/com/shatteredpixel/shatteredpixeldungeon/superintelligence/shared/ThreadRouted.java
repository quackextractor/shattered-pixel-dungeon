package com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared;

import com.badlogic.gdx.Gdx;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadRouted {

	private static final Map< Class< ? >, ThreadLocal< Object > > CURRENT = new ConcurrentHashMap<>();
	private static final Map< Class< ? >, Object > FALLBACK = new ConcurrentHashMap<>();
	private static final Map< Class< ? >, Object > PROXIES = new ConcurrentHashMap<>();

	private static volatile boolean enabled;

	private ThreadRouted(){}

	public static synchronized void enable(){
		if (enabled) return;
		enabled = true;
		if (Gdx.app != null) Gdx.app = route( com.badlogic.gdx.Application.class, Gdx.app );
		if (Gdx.files != null) Gdx.files = route( com.badlogic.gdx.Files.class, Gdx.files );
		if (Gdx.audio != null) Gdx.audio = route( com.badlogic.gdx.Audio.class, Gdx.audio );
		if (Gdx.graphics != null) Gdx.graphics = route( com.badlogic.gdx.Graphics.class, Gdx.graphics );
		if (Gdx.net != null) Gdx.net = route( com.badlogic.gdx.Net.class, Gdx.net );
	}

	public static < T > T route( Class< T > type, T service ){
		if (!enabled) return service;
		CURRENT.computeIfAbsent( type, t -> new ThreadLocal<>() ).set( service );
		FALLBACK.putIfAbsent( type, service );
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
