package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MirrorSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.WardSprite;
import com.watabou.gltextures.TextureCache;

import java.util.HashMap;
import java.util.Map;

public final class HeadlessSprites {

	private static final Map< Class< ? >, Class< ? > > standIns = new HashMap<>();

	private HeadlessSprites(){}

	public static CharSprite create( Char ch ){
		Class< ? > real = ch instanceof Mob ? ((Mob) ch).spriteClass : null;
		Class< ? > standIn = real == null ? HeadlessSprite.class : standInFor( real );
		try {
			return (CharSprite) standIn.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "could not build " + standIn.getName(), e );
		}
	}

	private static synchronized Class< ? > standInFor( Class< ? > real ){
		return standIns.computeIfAbsent( real, HeadlessSprites::generated );
	}

	private static Class< ? > generated( Class< ? > real ){
		if (WardSprite.class.isAssignableFrom( real )) sizeTextures( Assets.Sprites.WARDS );
		if (MirrorSprite.class.isAssignableFrom( real )){
			sizeTextures( Assets.Sprites.ROGUE );
			for (HeroClass cls : HeroClass.values()) sizeTextures( cls.spritesheet() );
		}
		String simple = real.getName().substring( real.getName().lastIndexOf( '.' ) + 1 ).replace( "$", "" );
		try {
			return Class.forName( HeadlessSprites.class.getPackage().getName() + ".Headless" + simple );
		} catch (ClassNotFoundException e){
			return HeadlessSprite.class;
		}
	}

	private static void sizeTextures( String asset ){
		if (TextureCache.contains( asset )) return;
		Pixmap sheet = new Pixmap( Gdx.files.internal( asset ) );
		TextureCache.create( asset, sheet.getWidth(), sheet.getHeight() );
		sheet.dispose();
	}
}
