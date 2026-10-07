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

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal libGDX service implementations so that the game's core logic - which reaches into
 * {@code Gdx} from all over the place (settings, saving, sound effects) - runs without a
 * graphics or audio backend.
 *
 * research.md: "bypass the android, ios, and desktop platform modules and operate strictly
 * within the Java based core module". That means {@code Gdx.app}, {@code Gdx.files} and
 * {@code Gdx.audio} must all be non-null even though nothing is ever drawn or heard.
 *
 * The only real side effect here is file IO, which is unavoidable because
 * {@code Dungeon.switchLevel} unconditionally calls {@code saveAll()}. {@link HeadlessFiles}
 * relocates that IO into a per-worker directory so parallel workers never fight over one
 * save file and a crashed worker cannot corrupt a real save.
 */
public class HeadlessServices {

	/** true once {@link #install} has run in this JVM. */
	public static boolean installed = false;

	/** Set true to make every Gdx.files write a no-op. See {@link NullingFiles}. */
	private static HeadlessFiles files;

	private HeadlessServices() {}

	public static void install( File saveDirectory ){
		loadNatives();

		files = new HeadlessFiles( saveDirectory );
		Gdx.app        = new App();
		Gdx.files      = files;
		Gdx.audio      = new SilentAudio();
		Gdx.input      = null;
		Gdx.graphics   = HeadlessGraphics.install();
		Gdx.net        = new SilentNet();

		//Gdx.gl staying null is the framework's "there is no renderer" signal. TextureCache,
		//Chrome and ShadowBox all branch on it, and layout code can still read a screen size from
		//Gdx.graphics. See HeadlessGraphics for why both are needed.
		Gdx.gl = null;
		Gdx.gl20 = null;
		Gdx.gl30 = null;
		Gdx.gl31 = null;
		Gdx.gl32 = null;

		//Every platform launcher does this as its first act, and every file path in the game
		//depends on it. AndroidLauncher/IOSLauncher use Local, DesktopLauncher picks based on
		//whether it is running from a jar. Headless we want everything inside the worker dir.
		com.watabou.utils.FileUtils.setDefaultFileProperties(
				com.badlogic.gdx.Files.FileType.Local, "" );

		installed = true;
	}

	/**
	 * Loads the libGDX native library.
	 *
	 * The desktop build never does this itself: {@code Lwjgl3Application} pulls it in through
	 * {@code Lwjgl3NativesLoader}. Headless replaces that backend wholesale, and a natives jar on the
	 * classpath does not load itself - nothing in libGDX bootstraps it without being asked. So the
	 * library was absent in every headless JVM, and only stayed unnoticed because almost nothing
	 * headless reaches native code.
	 *
	 * {@code TextureCache} is the exception. Its {@code getBitmap} is guarded on {@code Gdx.gl == null}
	 * and returns null, which is why decoding the item-icon film works at all. But its three
	 * programmatic constructors - {@code createSolid}, {@code createGradient} and {@code create} -
	 * build a {@code Pixmap} with no such guard, and a {@code Pixmap} allocates through
	 * {@code Gdx2DPixmap}. Those are reached from ordinary game logic, not from drawing: a
	 * {@code Flare} on roughly forty item and buff sites ({@code ScrollOfRemoveCurse},
	 * {@code PotionOfCleansing}, {@code Wand}, {@code Invulnerability}'s aura and so on), and a
	 * {@code ColorBlock} from {@code InventorySlot}, {@code InventoryPane} and {@code GameScene}.
	 * Any of them throws {@code UnsatisfiedLinkError} the first time an item is used.
	 *
	 * The scripted policy never hit one because it makes a narrow set of item choices. A policy
	 * sampling the action mask does, within a generation - which is how this surfaced: only once the
	 * worker stopped playing the scripted heuristic.
	 *
	 * Loading rather than guarding is the smaller change. Guarding those three constructors would
	 * alter behaviour for the real renderer, where they must keep working.
	 *
	 * Idempotent - libGDX guards it behind its own flag - so the trainer JVM and every worker can all
	 * call this on the way through.
	 */
	private static void loadNatives(){
		com.badlogic.gdx.utils.GdxNativesLoader.load();
	}

	/**
	 * Redirects every path the game writes to into the void. {@code Dungeon.switchLevel} calls
	 * saveAll() on every single floor, which means an IO syscall pair per floor change plus a
	 * full JSON serialisation of the hero. Over a million simulated turns that is pure waste,
	 * so the trainer runs with saving disabled and only turns it back on when it is about to
	 * hand a finished run to the replay viewer.
	 */
	public static void disableSaving( boolean disabled ){
		if (files != null) files.savingEnabled = !disabled;
	}

	public static boolean savingEnabled(){
		return files != null && files.savingEnabled;
	}

	// ---------------------------------------------------------------- Application

	/**
	 * {@code Game.runOnRenderThread} funnels through {@code Gdx.app.postRunnable}. In the real
	 * game that defers work to the GL thread; headlessly the actor thread already is the
	 * simulation thread, so we run the callback inline. Deferring would introduce
	 * nondeterminism between a callback that lands before the next turn and one that lands after.
	 */
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

	// ---------------------------------------------------------------- Files

	/**
	 * SPDSettings, GamesInProgress and Badges all persist through Gdx.files. We give each
	 * worker its own directory so N parallel workers do not interleave writes to the same
	 * {@code games} / {@code prefs} files.
	 */
	public static class HeadlessFiles implements Files {

		public final File root;

		/**
		 * When false every returned FileHandle points into {@link #sink}. This is the single
		 * biggest rollout speedup available: it removes a JSON serialisation of the hero plus
		 * two file syscalls from every single floor transition.
		 */
		public boolean savingEnabled = true;

		private final File sink;

		public HeadlessFiles( File root ){
			this.root = root.getAbsoluteFile();
			//noinspection ResultOfMethodCallIgnored
			this.root.mkdirs();
			this.sink = new File( root.getAbsoluteFile(), ".nosave" );
			//noinspection ResultOfMethodCallIgnored
			this.sink.mkdirs();
		}

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
					//Gdx.files.internal() is used for BOTH assets and saves (see
					//TextureCache.getBitmap and FileUtils.bundleToFile), and both arrive as
					//FileType.Local. They are told apart by name, which is unambiguous here:
					//every save the game writes is a ".dat" bundle under games/, and every asset is a
					//png/ogg/ttf/properties read from the jar. This mirrors what DesktopLauncher
					//resolves when it runs from a packaged jar.
					if (isSave( path )){
						File base = savingEnabled ? root : sink;
						return new FileHandle( new File( base, path ).getAbsolutePath() );
					}
					return classpathHandle( path );
			}
		}

/**
	 * True for paths the game writes to rather than reads from.
	 *
	 * Saves are GamesInProgress.gameFile/depthFile plus the handful of top level bundles in
	 * Badges, Bones, Rankings, SPDAction and Journal. All are ".dat" bundles written by
	 * FileUtils.bundleToFile, which stages them through a ".spdtmp" sibling first - hence both
	 * suffixes, since a half-written save has the temporary one.
	 */
		private static boolean isSave( String path ){
			String p = path.replace( '\\', '/' );
			return p.contains( ".dat" )
					|| p.contains( ".spdtmp" )
					|| p.startsWith( "games/" )
					|| p.startsWith( "game" );
		}

		/**
		 * Builds a FileHandle tagged FileType.Classpath.
		 *
		 * FileHandle's (String, FileType) constructor is protected, and the public (String)
		 * constructor always tags the handle Absolute. That distinction matters: a Classpath
		 * handle is read through the classloader (so assets packaged inside core-<ver>.jar are
		 * found), whereas an Absolute handle is read straight off the filesystem.
		 *
		 * A cache is kept because this is called once per texture, once per font and once per
		 * message file, and reflection lookup is not free.
		 */
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
	}

	/**
	 * libGDX ships no in-memory Preferences, and writing real prefs files for millions of
	 * simulated runs would dominate rollout cost. Straight string map, shared per prefs name so
	 * that repeated getPreferences() calls see each other's writes the way the game expects.
	 */
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

	// ---------------------------------------------------------------- Audio

	/**
	 * Sample.INSTANCE.play() is called from deep inside combat, pickup and trap code and would
	 * otherwise load real audio assets. A silent Sound keeps all of those call sites working for
	 * the cost of an interface dispatch.
	 */
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

	// ---------------------------------------------------------------- Net

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
