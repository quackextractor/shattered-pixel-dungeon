package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemorySaves {

	private final Map<String, byte[]> files = new HashMap<>();
	private final Map<String, Long> modified = new HashMap<>();
	private final File base;
	private long clock;

	public MemorySaves( File base ){
		this.base = base;
	}

	public FileHandle handle( String path ){
		String key = path.replace( '\\', '/' );
		while (key.startsWith( "/" )) key = key.substring( 1 );
		while (key.endsWith( "/" )) key = key.substring( 0, key.length() - 1 );
		return new Handle( key );
	}

	public synchronized boolean delete( String path ){
		return handle( path ).delete();
	}

	private synchronized void put( String key, byte[] data ){
		files.put( key, data );
		modified.put( key, ++clock );
	}

	private final class Handle extends FileHandle {

		private final String key;

		Handle( String key ){
			super( new File( base, key ));
			this.key = key;
		}

		@Override
		public InputStream read(){
			byte[] data;
			synchronized (MemorySaves.this){
				data = files.get( key );
			}
			if (data == null) throw new GdxRuntimeException( "File not found: " + key );
			return new ByteArrayInputStream( data );
		}

		@Override
		public OutputStream write( boolean append ){
			byte[] start;
			synchronized (MemorySaves.this){
				start = append ? files.get( key ) : null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(){
				@Override
				public void close(){
					put( key, toByteArray() );
				}
			};
			if (start != null) out.writeBytes( start );
			return out;
		}

		@Override
		public void writeBytes( byte[] bytes, boolean append ){
			writeBytes( bytes, 0, bytes.length, append );
		}

		@Override
		public void writeBytes( byte[] bytes, int offset, int length, boolean append ){
			OutputStream out = write( append );
			try {
				out.write( bytes, offset, length );
				out.close();
			} catch (java.io.IOException e){
				throw new GdxRuntimeException( e );
			}
		}

		@Override
		public boolean exists(){
			synchronized (MemorySaves.this){
				return files.containsKey( key ) || isDirectory();
			}
		}

		@Override
		public boolean isDirectory(){
			String prefix = key.isEmpty() ? "" : key + "/";
			synchronized (MemorySaves.this){
				for (String k : files.keySet()) if (k.startsWith( prefix )) return true;
			}
			return false;
		}

		@Override
		public FileHandle[] list(){
			String prefix = key.isEmpty() ? "" : key + "/";
			Set<String> names = new LinkedHashSet<>();
			synchronized (MemorySaves.this){
				for (String k : files.keySet()){
					if (!k.startsWith( prefix )) continue;
					String rest = k.substring( prefix.length() );
					int slash = rest.indexOf( '/' );
					names.add( slash < 0 ? rest : rest.substring( 0, slash ));
				}
			}
			List<FileHandle> out = new ArrayList<>();
			for (String n : names) out.add( new Handle( prefix + n ));
			return out.toArray( new FileHandle[ 0 ] );
		}

		@Override
		public FileHandle child( String name ){
			return handle( key.isEmpty() ? name : key + "/" + name );
		}

		@Override
		public FileHandle parent(){
			int slash = key.lastIndexOf( '/' );
			return new Handle( slash < 0 ? "" : key.substring( 0, slash ));
		}

		@Override
		public void mkdirs(){
		}

		@Override
		public boolean delete(){
			synchronized (MemorySaves.this){
				modified.remove( key );
				return files.remove( key ) != null;
			}
		}

		@Override
		public boolean deleteDirectory(){
			String prefix = key + "/";
			boolean any = delete();
			synchronized (MemorySaves.this){
				any |= files.keySet().removeIf( k -> k.startsWith( prefix ));
				modified.keySet().removeIf( k -> k.startsWith( prefix ));
			}
			return any;
		}

		@Override
		public void copyTo( FileHandle dest ){
			byte[] data;
			synchronized (MemorySaves.this){
				data = files.get( key );
			}
			if (data == null) throw new GdxRuntimeException( "File not found: " + key );
			dest.writeBytes( data, false );
		}

		@Override
		public void moveTo( FileHandle dest ){
			copyTo( dest );
			delete();
		}

		@Override
		public long length(){
			synchronized (MemorySaves.this){
				byte[] data = files.get( key );
				return data == null ? 0 : data.length;
			}
		}

		@Override
		public long lastModified(){
			synchronized (MemorySaves.this){
				Long t = modified.get( key );
				return t == null ? 0 : t;
			}
		}
	}
}
