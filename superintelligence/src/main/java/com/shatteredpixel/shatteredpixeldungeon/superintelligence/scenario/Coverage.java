package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class Coverage {

	public static final String PREFIX = "com.shatteredpixel.shatteredpixeldungeon.";

	private static final List< String > TREES = List.of( "actors.mobs", "items", "actors.hero.abilities",
			"levels.rooms.quest" );

	private static final List< String > PACKAGES = List.of( "actors.buffs", "actors.blobs", "levels.traps", "plants",
			"actors.hero.spells", "levels.rooms.special", "levels.rooms.secret" );

	private static final List< Class< ? > > BASES = List.of( Mob.class, Buff.class, Blob.class, Trap.class, Plant.class,
			Item.class, Weapon.Enchantment.class, Armor.Glyph.class, ArmorAbility.class, Recipe.class, Room.class,
			ClericSpell.class );

	private static final List< Class< ? > > MEMBER_BASES = List.of( Mob.class, Plant.class, Plant.Seed.class, Recipe.class );

	private static SortedSet< String > scanned;

	private Coverage(){}

	public static String key( Class< ? > type ){
		return type.getName().substring( PREFIX.length() );
	}

	public static String talent( Talent talent ){
		return "talent:" + talent.name();
	}

	public static String subclass( HeroSubClass subclass ){
		return "subclass:" + subclass.name();
	}

	public static String heroClass( HeroClass heroClass ){
		return "heroclass:" + heroClass.name();
	}

	public static synchronized SortedSet< String > scanned(){
		if (scanned == null){
			SortedSet< String > out = new TreeSet<>();
			for (String name : classNames()){
				Class< ? > type = load( name );
				if (type != null && counts( type )) out.add( key( type ));
			}
			scanned = out;
		}
		return new TreeSet<>( scanned );
	}

	public static SortedSet< String > enums(){
		SortedSet< String > out = new TreeSet<>();
		for (Talent talent : Talent.values()) out.add( talent( talent ));
		for (HeroSubClass subclass : HeroSubClass.values()) if (subclass != HeroSubClass.NONE) out.add( subclass( subclass ));
		for (HeroClass heroClass : HeroClass.values()) out.add( heroClass( heroClass ));
		return out;
	}

	public static SortedSet< String > extra(){
		return new TreeSet<>( List.of( "items.wands.CursedWand",
				"actors.hero.abilities.duelist.Feint$AfterImage" ));
	}

	public static SortedSet< String > universe(){
		SortedSet< String > out = scanned();
		out.addAll( enums() );
		out.addAll( extra() );
		return out;
	}

	public static SortedMap< String, String > excluded(){
		SortedMap< String, String > out = new TreeMap<>();
		for (String line : resource( "excluded.txt" )){
			int tab = line.indexOf( '\t' );
			if (tab < 0) throw new IllegalStateException( "coverage/excluded.txt: no tab between key and reason in: " + line );
			out.put( line.substring( 0, tab ), line.substring( tab + 1 ));
		}
		return out;
	}

	public static SortedSet< String > denominator(){
		SortedSet< String > out = universe();
		out.removeAll( excluded().keySet() );
		return out;
	}

	public static SortedSet< String > declared( List< Scenario > scenarios ){
		SortedSet< String > out = new TreeSet<>();
		for (Scenario scenario : scenarios) out.addAll( scenario.covers );
		return out;
	}

	public static SortedSet< String > missing( Set< String > denominator, Set< String > declared ){
		SortedSet< String > out = new TreeSet<>( denominator );
		out.removeAll( declared );
		return out;
	}

	private static boolean counts( Class< ? > type ){
		if (type.isAnonymousClass() || type.isLocalClass() || type.isInterface()) return false;
		if (Modifier.isAbstract( type.getModifiers() )) return false;
		String name = key( type );
		String pkg = name.substring( 0, Math.max( 0, name.lastIndexOf( '.' )));
		if (pkg.equals( "levels" )) return !type.isMemberClass() && Level.class.isAssignableFrom( type );
		if (!inScannedPackage( pkg )) return false;
		return assignable( type, type.isMemberClass() ? MEMBER_BASES : BASES );
	}

	private static boolean inScannedPackage( String pkg ){
		if (PACKAGES.contains( pkg )) return true;
		for (String tree : TREES) if (pkg.equals( tree ) || pkg.startsWith( tree + "." )) return true;
		return false;
	}

	private static boolean assignable( Class< ? > type, List< Class< ? > > bases ){
		for (Class< ? > base : bases) if (base.isAssignableFrom( type )) return true;
		return false;
	}

	private static Class< ? > load( String name ){
		try {
			return Class.forName( name, false, Coverage.class.getClassLoader() );
		} catch (Throwable t){
			return null;
		}
	}

	private static List< String > classNames(){
		String root = PREFIX.replace( '.', '/' );
		List< String > out = new ArrayList<>();
		try {
			File source = new File( Dungeon.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
			if (source.isDirectory()){
				Path base = source.toPath();
				try (Stream< Path > walk = Files.walk( base )){
					walk.forEach( p -> {
						String entry = base.relativize( p ).toString().replace( File.separatorChar, '/' );
						if (entry.startsWith( root ) && entry.endsWith( ".class" )) out.add( className( entry ));
					});
				}
			} else {
				try (JarFile jar = new JarFile( source )){
					jar.stream().map( e -> e.getName() )
							.filter( entry -> entry.startsWith( root ) && entry.endsWith( ".class" ))
							.forEach( entry -> out.add( className( entry )));
				}
			}
		} catch (IOException e){
			throw new UncheckedIOException( e );
		} catch (java.net.URISyntaxException e){
			throw new IllegalStateException( e );
		}
		return out;
	}

	private static String className( String entry ){
		return entry.substring( 0, entry.length() - ".class".length() ).replace( '/', '.' );
	}

	private static List< String > resource( String name ){
		List< String > out = new ArrayList<>();
		try (InputStream in = Coverage.class.getResourceAsStream( "/coverage/" + name )){
			if (in == null) throw new IllegalStateException( "coverage/" + name + " is not on the classpath" );
			BufferedReader reader = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ));
			for (String line; (line = reader.readLine()) != null; ){
				if (!line.isBlank()) out.add( line.strip() );
			}
		} catch (IOException e){
			throw new UncheckedIOException( e );
		}
		return out;
	}
}
