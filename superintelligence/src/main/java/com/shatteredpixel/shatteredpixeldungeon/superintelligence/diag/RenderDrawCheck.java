package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.Random;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
public class RenderDrawCheck {
	private static final String RANDOM = "com/watabou/utils/Random";
	private static final Set< String > STACK_METHODS = Set.of( "pushGenerator", "popGenerator", "reseedBase", "resetGenerators" );
	static final Map< String, String > ALLOWED = new LinkedHashMap<>();
	static {
		String outsideARun = "a scene or window no recorded run opens";
		String bothSides = "constructed by gameplay in both environments, so both spend the draw";
		ALLOWED.put( "com.watabou.noosa.Group#random", "a helper with no caller in the game" );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.ui.BadgesGrid", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.ui.TalentsPane", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.scenes.SurfaceScene", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.scenes.AmuletScene", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.windows.WndSettings", outsideARun );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.windows.WndChooseAbility", bothSides );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.windows.WndChooseSubclass", bothSides );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfMetamorphosis$WndMetamorphReplace", bothSides );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.items.spells.Alchemize$WndAlchemizeItem", bothSides );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon$1#onSelect", bothSides );
		ALLOWED.put( "com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDivineInspiration$1#onSelect", bothSides );
	}
	public static void main( String[] args ){
		List< String > findings = scan( args.length > 0 ? args[ 0 ] : null );
		Set< String > unused = new LinkedHashSet<>( ALLOWED.keySet() );
		List< String > failures = new ArrayList<>();
		for (String f : findings){
			String allowedBy = allowedBy( f );
			if (allowedBy == null) failures.add( f );
			else unused.remove( allowedBy );
		}
		for (String stale : unused){
			failures.add( "allow-list entry matches nothing any more, remove it: " + stale );
		}
		if (failures.isEmpty()){
			System.out.println( "[OK]     render-only draws: " + findings.size()
					+ " gameplay draws in presentation code, every one allow-listed with a reason" );
		} else {
			System.out.println( "[ERROR]  render-only draws: " + failures.size()
					+ " presentation method(s) draw from the gameplay RNG; use PRandom" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
	static String allowedBy( String finding ){
		for (String key : ALLOWED.keySet()){
			if (finding.equals( key ) || finding.startsWith( key + "#" ) || finding.startsWith( key + "$" )) return key;
		}
		return null;
	}
	static List< String > scan( String onlyPrefix ){
		List< String > findings = new ArrayList<>();
		ClassLoader loader = RenderDrawCheck.class.getClassLoader();
		for (Class< ? > anchor : new Class< ? >[]{ Random.class, Dungeon.class }){
			forEachClass( anchor, (name, bytes) -> {
				if (onlyPrefix != null && !name.startsWith( onlyPrefix )) return;
				for (String method : drawingMethods( bytes, isPresentation( name, loader ) )){
					findings.add( name + "#" + method );
				}
			});
		}
		return findings;
	}
	static boolean isPresentation( String name, ClassLoader loader ){
		try {
			Class< ? > c = Class.forName( name, false, loader );
			return Gizmo.class.isAssignableFrom( c ) || Emitter.Factory.class.isAssignableFrom( c );
		} catch (Throwable notLoadable){
			return false;
		}
	}
	interface ClassVisitor { void visit( String name, byte[] bytes ) throws IOException; }
	private static void forEachClass( Class< ? > anchor, ClassVisitor visitor ){
		try {
			Path root = Path.of( anchor.getProtectionDomain().getCodeSource().getLocation().toURI() );
			if (Files.isDirectory( root )){
				try (Stream< Path > walk = Files.walk( root )){
					for (Path p : (Iterable< Path >) walk.filter( q -> q.toString().endsWith( ".class" ) )::iterator){
						String rel = root.relativize( p ).toString().replace( File.separatorChar, '/' );
						visitor.visit( rel.substring( 0, rel.length() - 6 ).replace( '/', '.' ), Files.readAllBytes( p ) );
					}
				}
			} else {
				try (JarFile jar = new JarFile( root.toFile() )){
					for (Enumeration< JarEntry > e = jar.entries(); e.hasMoreElements(); ){
						JarEntry entry = e.nextElement();
						if (!entry.getName().endsWith( ".class" )) continue;
						try (InputStream in = jar.getInputStream( entry )){
							ByteArrayOutputStream out = new ByteArrayOutputStream();
							in.transferTo( out );
							String n = entry.getName();
							visitor.visit( n.substring( 0, n.length() - 6 ).replace( '/', '.' ), out.toByteArray() );
						}
					}
				}
			}
		} catch (IOException | URISyntaxException e){
			throw new IllegalStateException( "cannot read the classes beside " + anchor.getName(), e );
		}
	}
	static List< String > drawingMethods( byte[] bytes, boolean presentationClass ) throws IOException {
		DataInputStream in = new DataInputStream( new java.io.ByteArrayInputStream( bytes ));
		in.readInt(); in.readUnsignedShort(); in.readUnsignedShort();
		int count = in.readUnsignedShort();
		String[] utf = new String[ count ];
		int[] refClass = new int[ count ], refNat = new int[ count ], natName = new int[ count ], classIndex = new int[ count ];
		boolean[] isMethodRef = new boolean[ count ];
		for (int i = 1; i < count; i++){
			int tag = in.readUnsignedByte();
			switch (tag){
				case 1: utf[ i ] = in.readUTF(); break;
				case 7: classIndex[ i ] = in.readUnsignedShort(); break;
				case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
				case 9: case 10: case 11:
					isMethodRef[ i ] = tag != 9;
					refClass[ i ] = in.readUnsignedShort();
					refNat[ i ] = in.readUnsignedShort();
					break;
				case 12: natName[ i ] = in.readUnsignedShort(); in.readUnsignedShort(); break;
				case 3: case 4: case 17: case 18: in.readInt(); break;
				case 5: case 6: in.readLong(); i++; break;
				case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
				default: throw new IOException( "unknown constant pool tag " + tag );
			}
		}
		Set< Integer > draws = new LinkedHashSet<>(), stack = new LinkedHashSet<>();
		for (int i = 1; i < count; i++){
			if (!isMethodRef[ i ] || !RANDOM.equals( utf[ classIndex[ refClass[ i ] ] ] )) continue;
			String name = utf[ natName[ refNat[ i ] ] ];
			(STACK_METHODS.contains( name ) ? stack : draws).add( i );
		}
		List< String > out = new ArrayList<>();
		if (draws.isEmpty()) return out;
		in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
		skip( in, in.readUnsignedShort() * 2 );
		int fields = in.readUnsignedShort();
		for (int f = 0; f < fields; f++){
			skip( in, 6 );
			skipAttributes( in );
		}
		int methods = in.readUnsignedShort();
		for (int m = 0; m < methods; m++){
			in.readUnsignedShort();
			String name = utf[ in.readUnsignedShort() ];
			in.readUnsignedShort();
			int attributes = in.readUnsignedShort();
			boolean drawsHere = false, pushesHere = false;
			for (int a = 0; a < attributes; a++){
				String attr = utf[ in.readUnsignedShort() ];
				int length = in.readInt();
				if (!"Code".equals( attr )){ skip( in, length ); continue; }
				in.readUnsignedShort(); in.readUnsignedShort();
				byte[] code = new byte[ in.readInt() ];
				in.readFully( code );
				for (int target : invokedStatics( code )){
					drawsHere |= draws.contains( target );
					pushesHere |= stack.contains( target );
				}
				skip( in, in.readUnsignedShort() * 8 );
				skipAttributes( in );
			}
			if (drawsHere && !pushesHere && (presentationClass || "staffFx".equals( name ))) out.add( name );
		}
		return out;
	}
	private static void skip( DataInputStream in, int n ) throws IOException {
		in.skipNBytes( n );
	}
	private static void skipAttributes( DataInputStream in ) throws IOException {
		int n = in.readUnsignedShort();
		for (int a = 0; a < n; a++){
			in.readUnsignedShort();
			skip( in, in.readInt() );
		}
	}
	static List< Integer > invokedStatics( byte[] code ){
		List< Integer > out = new ArrayList<>();
		int pc = 0;
		while (pc < code.length){
			int op = code[ pc ] & 0xff;
			if (op == 0xb8) out.add( ((code[ pc + 1 ] & 0xff) << 8) | (code[ pc + 2 ] & 0xff) );
			pc += length( code, pc, op );
		}
		return out;
	}
	private static int length( byte[] code, int pc, int op ){
		if (op == 0xaa){
			int p = (pc + 4) & ~3;
			int low = readInt( code, p + 4 ), high = readInt( code, p + 8 );
			return p - pc + 12 + 4 * (high - low + 1);
		}
		if (op == 0xab){
			int p = (pc + 4) & ~3;
			return p - pc + 8 + 8 * readInt( code, p + 4 );
		}
		if (op == 0xc4) return (code[ pc + 1 ] & 0xff) == 0x84 ? 6 : 4;
		if (op == 0x10 || op == 0x12 || (op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3a) || op == 0xa9 || op == 0xbc) return 2;
		if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || (op >= 0x99 && op <= 0xa8) || (op >= 0xb2 && op <= 0xb8) || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1 || op == 0xc6 || op == 0xc7) return 3;
		if (op == 0xc5) return 4;
		if (op == 0xb9 || op == 0xba || op == 0xc8 || op == 0xc9) return 5;
		return 1;
	}
	private static int readInt( byte[] b, int p ){
		return ((b[ p ] & 0xff) << 24) | ((b[ p + 1 ] & 0xff) << 16) | ((b[ p + 2 ] & 0xff) << 8) | (b[ p + 3 ] & 0xff);
	}
}
