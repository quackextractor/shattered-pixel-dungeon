package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.net.ServerSocket;
import com.badlogic.gdx.net.ServerSocketHints;
import com.badlogic.gdx.net.Socket;
import com.badlogic.gdx.net.SocketHints;
import com.badlogic.gdx.utils.Clipboard;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.ThreadRouted;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class HeadlessServices {

	public static boolean installed = false;

	private static HeadlessFiles files;

	private HeadlessServices() {}

	public static void install( File saveDirectory ){
		loadNatives();

		files = new HeadlessFiles( saveDirectory );
		Gdx.app        = ThreadRouted.route( Application.class, new App() );
		Gdx.files      = ThreadRouted.route( Files.class, files );
		Gdx.audio      = ThreadRouted.route( Audio.class, new SilentAudio() );
		Gdx.input      = null;
		Gdx.graphics   = ThreadRouted.route( com.badlogic.gdx.Graphics.class, HeadlessGraphics.install() );
		Gdx.net        = ThreadRouted.route( Net.class, new SilentNet() );

		Gdx.gl = null;
		Gdx.gl20 = null;
		Gdx.gl30 = null;
		Gdx.gl31 = null;
		Gdx.gl32 = null;

		com.watabou.utils.FileUtils.setDefaultFileProperties(
				com.badlogic.gdx.Files.FileType.Local, "" );

		installed = true;
	}

	private static void loadNatives(){
		com.badlogic.gdx.utils.GdxNativesLoader.load();
	}

	public static HeadlessFiles files(){
		return files;
	}

	public static void disableSaving( boolean disabled ){
		if (files != null) files.savingEnabled = !disabled;
	}

	public static boolean savingEnabled(){
		return files != null && files.savingEnabled;
	}

	public static File saveDirectory(){
		return files.savingEnabled ? files.root : files.sink;
	}

	public static class App implements Application {

		public final ApplicationLogger logger = new ApplicationLogger() {
			@Override public void log   (String tag, String message) { }
			@Override public void log   (String tag, String message, Throwable exception) { }
			@Override public void error (String tag, String message) { }
			@Override public void error (String tag, String message, Throwable exception) { }
			@Override public void debug (String tag, String message) { }
			@Override public void debug (String tag, String message, Throwable exception) { }
		};
		@Override public ApplicationListener getApplicationListener() { return null; }
		@Override public com.badlogic.gdx.Graphics getGraphics() { return null; }
		@Override public Audio getAudio() { return Gdx.audio; }
		@Override public com.badlogic.gdx.Input getInput() { return null; }
		@Override public Files getFiles() { return Gdx.files; }
		@Override public Net getNet() { return Gdx.net; }
		@Override public void log(String tag, String message) { }
		@Override public void log(String tag, String message, Throwable exception) { }
		@Override public void error(String tag, String message) { }
		@Override public void error(String tag, String message, Throwable exception) { }
		@Override public void debug(String tag, String message) { }
		@Override public void debug(String tag, String message, Throwable exception) { }
		@Override public void setLogLevel(int logLevel) { }
		@Override public int getLogLevel() { return LOG_NONE; }
		@Override public void setApplicationLogger(ApplicationLogger applicationLogger) { }
		@Override public ApplicationLogger getApplicationLogger() { return logger; }
		@Override public ApplicationType getType() { return ApplicationType.HeadlessDesktop; }
		@Override public int getVersion() { return 1; }
		@Override public long getJavaHeap() { return Runtime.getRuntime().totalMemory(); }
		@Override public long getNativeHeap() { return 0; }
		@Override public Preferences getPreferences(String name) { return new MemoryPreferences( name ); }
		@Override public Clipboard getClipboard() { return new Clipboard() {
			@Override public String getContents() { return ""; }
			@Override public void setContents(String contents) { }
			@Override public boolean hasContents() { return false; }
		}; }
		@Override public void postRunnable(Runnable runnable) { runnable.run(); }
		@Override public void exit() { }
		@Override public void addLifecycleListener(LifecycleListener listener) { }
		@Override public void removeLifecycleListener(LifecycleListener listener) { }
	}

	public static class HeadlessFiles implements Files {

		public final File root;

		public boolean savingEnabled = true;

		private final File sink;

		public HeadlessFiles( File root ){
			this.root = root.getAbsoluteFile();

			this.root.mkdirs();
			this.sink = com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Scratch.dir( "nosave" );
			this.memory = new MemorySaves( sink );
		}

		private final MemorySaves memory;

		@Override public FileHandle getFileHandle(String path, FileType type) {
			switch (type) {
				case Absolute:
					return new FileHandle( new File( path ).getAbsolutePath() );

				case Classpath:
					return classpathHandle( path );

				case External:
					return new FileHandle( new File( root, path ).getAbsolutePath() );

				case Local:
				default:

					if (isSave( path )){
						if (!savingEnabled) return memory.handle( path );
						return new FileHandle( new File( root, path ).getAbsolutePath() );
					}
					return classpathHandle( path );
			}
		}

		private static boolean isSave( String path ){
			String p = path.replace( '\\', '/' );
			return p.contains( ".dat" )
					|| p.contains( ".spdtmp" )
					|| p.startsWith( "games/" )
					|| p.startsWith( "game" );
		}

		private FileHandle classpathHandle( String path ){
			FileHandle cached = classpathCache.get( path );
			if (cached != null) return cached;

			FileHandle handle;
			try {
				Constructor<FileHandle> ctor =
						FileHandle.class.getDeclaredConstructor( String.class, Files.FileType.class );
				ctor.setAccessible( true );
				handle = ctor.newInstance( path, Files.FileType.Classpath );
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(
						"libGDX FileHandle constructor changed; HeadlessFiles cannot resolve classpath assets", e );
			}

			classpathCache.put( path, handle );
			return handle;
		}

		private final Map<String, FileHandle> classpathCache = new HashMap<>();
		@Override public FileHandle classpath(String path) { return getFileHandle( path, FileType.Classpath ); }
		@Override public FileHandle internal(String path)  { return getFileHandle( path, FileType.Local ); }
		@Override public FileHandle external(String path)  { return getFileHandle( path, FileType.External ); }
		@Override public FileHandle absolute(String path)  { return getFileHandle( path, FileType.Absolute ); }
		@Override public FileHandle local(String path)      { return getFileHandle( path, FileType.Local ); }
		@Override public String getExternalStoragePath() { return root.getAbsolutePath(); }
		@Override public boolean isExternalStorageAvailable() { return true; }
		@Override public String getLocalStoragePath() { return root.getAbsolutePath(); }
		@Override public boolean isLocalStorageAvailable() { return true; }

		public boolean deleteSave( String name ){
			if (!savingEnabled) return memory.delete( name ) | memory.delete( name + ".spdtmp" );
			boolean existed = new File( root, name ).delete();
			existed |= new File( root, name + ".spdtmp" ).delete();
			return existed;
		}
	}

	public static class MemoryPreferences implements Preferences {

		private static final Map<String, Map<String, String>> stores = new HashMap<>();

		private final Map<String, String> store;

		public MemoryPreferences( String name ){
			synchronized (stores){
				store = stores.computeIfAbsent( name, k -> new LinkedHashMap<>() );
			}
		}

		@Override public Preferences putBoolean(String key, boolean val){ putRaw( key, String.valueOf( val ) ); return this; }
		@Override public Preferences putInteger(String key, int val)    { putRaw( key, String.valueOf( val ) ); return this; }
		@Override public Preferences putLong(String key, long val)      { putRaw( key, String.valueOf( val ) ); return this; }
		@Override public Preferences putFloat(String key, float val)    { putRaw( key, String.valueOf( val ) ); return this; }
		@Override public Preferences putString(String key, String val)  { putRaw( key, val ); return this; }
		@Override public Preferences put(Map<String, ?> vals) {
			for (Map.Entry<String, ?> e : vals.entrySet()){
				if (e.getValue() instanceof Boolean)      putBoolean( e.getKey(), (Boolean)e.getValue() );
				else if (e.getValue() instanceof Integer) putInteger( e.getKey(), (Integer)e.getValue() );
				else if (e.getValue() instanceof Long)    putLong( e.getKey(), (Long)e.getValue() );
				else if (e.getValue() instanceof Float)   putFloat( e.getKey(), (Float)e.getValue() );
				else                                       putString( e.getKey(), String.valueOf( e.getValue() ) );
			}
			return this;
		}
		@Override public boolean getBoolean(String key) { return getBoolean( key, false ); }
		@Override public int getInteger(String key) { return getInteger( key, 0 ); }
		@Override public long getLong(String key) { return getLong( key, 0L ); }
		@Override public float getFloat(String key) { return getFloat( key, 0f ); }
		@Override public String getString(String key) { return getString( key, "" ); }
		@Override public boolean getBoolean(String key, boolean defValue) {
			String v = getRaw( key );
			return v == null ? defValue : Boolean.parseBoolean( v );
		}
		@Override public int getInteger(String key, int defValue) {
			String v = getRaw( key );
			try { return v == null ? defValue : Integer.parseInt( v ); } catch (NumberFormatException e){ return defValue; }
		}
		@Override public long getLong(String key, long defValue) {
			String v = getRaw( key );
			try { return v == null ? defValue : Long.parseLong( v ); } catch (NumberFormatException e){ return defValue; }
		}
		@Override public float getFloat(String key, float defValue) {
			String v = getRaw( key );
			try { return v == null ? defValue : Float.parseFloat( v ); } catch (NumberFormatException e){ return defValue; }
		}
		@Override public String getString(String key, String defValue) {
			String v = getRaw( key );
			return v == null ? defValue : v;
		}
		@Override public Map<String, ?> get() { synchronized (store){ return new LinkedHashMap<>( store ); } }
		@Override public boolean contains(String key) { return getRaw( key ) != null; }
		@Override public void clear() { synchronized (store){ store.clear(); } }
		@Override public void remove(String key) { synchronized (store){ store.remove( key ); } }
		@Override public void flush() { }

		private String getRaw( String key ){ synchronized (store){ return store.get( key ); } }
		private void putRaw( String key, String value ){ synchronized (store){ store.put( key, value ); } }
	}

	public static class SilentAudio implements Audio {
		@Override public AudioDevice newAudioDevice(int sampleRate, boolean isMono) { return null; }
		@Override public AudioRecorder newAudioRecorder(int sampleRate, boolean isMono) { return null; }
		@Override public Sound newSound(FileHandle file) { return SilentSound.INSTANCE; }
		@Override public Music newMusic(FileHandle file) { return SilentMusic.INSTANCE; }
		@Override public boolean switchOutputDevice(String audioDeviceName) { return false; }
		@Override public String[] getAvailableOutputDevices() { return new String[0]; }
	}

	public static class SilentSound implements Sound {
		public static final SilentSound INSTANCE = new SilentSound();
		@Override public long play() { return 0; }
		@Override public long play(float volume) { return 0; }
		@Override public long play(float volume, float pitch, float pan) { return 0; }
		@Override public long loop() { return 0; }
		@Override public long loop(float volume) { return 0; }
		@Override public long loop(float volume, float pitch, float pan) { return 0; }
		@Override public void stop() { }
		@Override public void pause() { }
		@Override public void resume() { }
		@Override public void dispose() { }
		@Override public void stop(long soundId) { }
		@Override public void pause(long soundId) { }
		@Override public void resume(long soundId) { }
		@Override public void setLooping(long soundId, boolean looping) { }
		@Override public void setPitch(long soundId, float pitch) { }
		@Override public void setVolume(long soundId, float volume) { }
		@Override public void setPan(long soundId, float volume, float pan) { }
	}

	public static class SilentMusic implements Music {
		public static final SilentMusic INSTANCE = new SilentMusic();
		@Override public void play() { }
		@Override public void pause() { }
		@Override public void stop() { }
		@Override public boolean isPlaying() { return false; }
		@Override public void setLooping(boolean isLooping) { }
		@Override public boolean isLooping() { return false; }
		@Override public void setVolume(float volume) { }
		@Override public float getVolume() { return 0; }
		@Override public void setPan(float pan, float volume) { }
		@Override public void setPosition(float position) { }
		@Override public float getPosition() { return 0; }
		@Override public void dispose() { }
		@Override public void setOnCompletionListener(OnCompletionListener listener) { }
	}

	public static class SilentNet implements Net {
		@Override public void sendHttpRequest(HttpRequest request, HttpResponseListener responseListener) { }
		@Override public void cancelHttpRequest(HttpRequest request) { }
		@Override public boolean isHttpRequestPending(HttpRequest request) { return false; }
		@Override public ServerSocket newServerSocket(Protocol protocol, String host, int port, ServerSocketHints hints) { return null; }
		@Override public ServerSocket newServerSocket(Protocol protocol, int port, ServerSocketHints hints) { return null; }
		@Override public Socket newClientSocket(Protocol protocol, String host, int port, SocketHints hints) { return null; }
		@Override public boolean openURI(String uri) { return false; }
	}
}
