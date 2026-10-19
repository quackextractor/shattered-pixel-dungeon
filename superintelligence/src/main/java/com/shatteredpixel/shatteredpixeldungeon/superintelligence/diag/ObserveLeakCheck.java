package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfLullaby;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTerror;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatshield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RoundShield;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.ObservationEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ObserveLeakCheck {

	private ObserveLeakCheck(){}

	static void checkInventoryShowsOnlyWhatThePlayerSees(){
		List< Item > potions = new ArrayList<>();
		List< Item > scrolls = new ArrayList<>();
		for (Item item : new Item[]{ new PotionOfStrength(), new PotionOfToxicGas(), new PotionOfFrost(),
				new PotionOfMindVision(), new ScrollOfUpgrade(), new ScrollOfTerror(), new ScrollOfLullaby(),
				new ScrollOfMirrorImage() }){
			if (item.isIdentified()) continue;
			(item instanceof Potion ? potions : scrolls).add( item );
		}
		if (potions.size() < 2 || scrolls.size() < 2){
			ObserveCheck.fail( "the fixture needs two unidentified potions and two unidentified scrolls" );
			return;
		}
		Item plain = new LeatherArmor();
		Item hidden = new LeatherArmor().upgrade( 3 );
		hidden.cursed = true;
		sameEncoding( "two unidentified potions", potions.get( 0 ), potions.get( 1 ));
		sameEncoding( "two unidentified scrolls", scrolls.get( 0 ), scrolls.get( 1 ));
		sameEncoding( "armor with an unknown +3 and an unknown curse", plain, hidden );
	}

	static void checkHeroFeaturesHideAnUnknownLevel(){
		Hero hero = Dungeon.hero;
		KindOfWeapon worn = hero.belongings.weapon;
		hideAnUnknownLevel( hero );
		hero.STR += 10;
		for (KindOfWeapon weapon : new KindOfWeapon[]{ new Greatshield(), new RoundShield() }){
			hero.belongings.weapon = weapon;
			hideAnUnknownLevel( hero );
		}
		hero.STR -= 10;
		hero.belongings.weapon = worn;
	}

	private static void hideAnUnknownLevel( Hero hero ){
		Item weapon = hero.belongings.weapon();
		Item armor = hero.belongings.armor();
		if (weapon == null || armor == null){
			ObserveCheck.fail( "the fixture hero carries no weapon or no armor" );
			return;
		}
		boolean weaponKnown = weapon.levelKnown;
		boolean armorKnown = armor.levelKnown;
		weapon.levelKnown = false;
		armor.levelKnown = false;
		float[] before = new float[ HeroEncoder.FEATURES ];
		float[] after = new float[ HeroEncoder.FEATURES ];
		HeroEncoder.encode( hero, before );
		weapon.level( weapon.level() + 3 );
		armor.level( armor.level() + 3 );
		HeroEncoder.encode( hero, after );
		weapon.level( weapon.level() - 3 );
		armor.level( armor.level() - 3 );
		weapon.levelKnown = weaponKnown;
		armor.levelKnown = armorKnown;
		if (!Arrays.equals( before, after )){
			ObserveCheck.fail( "an unknown +3 on the equipped " + weapon.name() + " and armor changed the hero features: "
					+ Arrays.toString( before ) + " vs " + Arrays.toString( after ));
		}
	}

	static void checkAnUnseenFloorEncodesToNothing( ObservationEncoder encoder ){
		Level level = Dungeon.level;
		boolean[] visited = level.visited.clone();
		boolean[] mapped = level.mapped.clone();
		boolean[] fov = level.heroFOV.clone();
		Arrays.fill( level.visited, false );
		Arrays.fill( level.mapped, false );
		Arrays.fill( level.heroFOV, false );
		encoder.resetExplored();
		encoder.encode();
		int lit = 0;
		for (float v : encoder.grid()) if (v != 0f) lit++;
		System.arraycopy( visited, 0, level.visited, 0, visited.length );
		System.arraycopy( mapped, 0, level.mapped, 0, mapped.length );
		System.arraycopy( fov, 0, level.heroFOV, 0, fov.length );
		encoder.resetExplored();
		if (lit != 0){
			ObserveCheck.fail( "a floor with nothing seen, visited or mapped still lit " + lit + " grid values,"
					+ " so the observation draws the layout the player has not uncovered" );
		}
	}

	static void checkTheWindowIsCentredOnTheHero( ObservationEncoder encoder ){
		encoder.encode();
		int plane = 0;
		for (GridChannel c : GridChannel.values()){
			if (c == GridChannel.HERO) break;
			if (c.kind != GridChannel.Kind.SCALAR) plane++;
		}
		int area = encoder.gridWidth() * encoder.gridHeight();
		int found = -1;
		for (int p = 0; p < area; p++) if (encoder.grid()[ plane * area + p ] != 0f) found = p;
		int gx = found % encoder.gridWidth();
		int gy = found / encoder.gridWidth();
		float cx = (encoder.gridWidth() - 1) / 2f;
		float cy = (encoder.gridHeight() - 1) / 2f;
		if (found < 0 || Math.abs( gx - cx ) > 1f || Math.abs( gy - cy ) > 1f){
			ObserveCheck.fail( "the hero is drawn at grid (" + gx + "," + gy + "), not the centre of a "
					+ encoder.gridWidth() + "x" + encoder.gridHeight() + " window" );
		}
	}

	private static void sameEncoding( String what, Item a, Item b ){
		int width = InventoryEncoder.FEATURES_PER_SLOT;
		float[] out = new float[ 2 * width ];
		InventoryEncoder.encode( Arrays.asList( a, b ), out, 2 );
		float[] first = Arrays.copyOfRange( out, 0, width );
		float[] second = Arrays.copyOfRange( out, width, 2 * width );
		if (!Arrays.equals( first, second )){
			ObserveCheck.fail( what + " encode differently, so the inventory shows what the game hides: "
					+ Arrays.toString( first ) + " vs " + Arrays.toString( second ));
		}
	}
}
