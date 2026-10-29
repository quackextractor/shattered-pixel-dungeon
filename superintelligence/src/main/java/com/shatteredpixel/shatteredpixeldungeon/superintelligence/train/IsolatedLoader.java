package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

final class IsolatedLoader extends URLClassLoader {

	private static final String[] SHARED = {
			"java.", "javax.", "jdk.", "sun.", "com.sun.", "com.badlogic.", "org.lwjgl.",
			"com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared."
	};

	IsolatedLoader( ClassLoader parent ){
		super( classpath(), parent );
	}

	private static URL[] classpath(){
		List< URL > urls = new ArrayList<>();
		for (String entry : System.getProperty( "java.class.path" ).split( File.pathSeparator )){
			try {
				urls.add( new File( entry ).toURI().toURL() );
			} catch (MalformedURLException e){
				throw new IllegalStateException( "classpath entry " + entry + " is not a path", e );
			}
		}
		return urls.toArray( new URL[ 0 ] );
	}

	private static boolean shared( String name ){
		for (String prefix : SHARED) if (name.startsWith( prefix )) return true;
		return false;
	}

	@Override
	protected Class< ? > loadClass( String name, boolean resolve ) throws ClassNotFoundException {
		if (shared( name )) return super.loadClass( name, resolve );
		synchronized (getClassLoadingLock( name )){
			Class< ? > c = findLoadedClass( name );
			if (c == null){
				try {
					c = findClass( name );
				} catch (ClassNotFoundException e){
					return super.loadClass( name, resolve );
				}
			}
			if (resolve) resolveClass( c );
			return c;
		}
	}
}
