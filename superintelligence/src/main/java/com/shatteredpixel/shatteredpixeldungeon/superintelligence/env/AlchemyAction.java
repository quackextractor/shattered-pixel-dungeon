package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.LiquidMetal;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MeatPie;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AlchemyAction {

	private AlchemyAction(){}

	public static final int MAX_INPUTS = 3;

	public static int adjacentPot( Hero hero, int targetCount ){
		if (Dungeon.level == null || hero == null) return -1;

		int n = Math.min( targetCount, ActionMapper.TARGET_OFFSETS.length );
		for (int o = 0; o < n; o++){
			int[] offset = ActionMapper.TARGET_OFFSETS[ o ];
			int cell = hero.pos + offset[ 0 ] + offset[ 1 ] * Dungeon.level.width();
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (Dungeon.level.map[ cell ] == Terrain.ALCHEMY ) return cell;
		}
		return -1;
	}


	public static List< ArrayList< Item > > ingredientSets( Hero hero ){
		List< ArrayList< Item > > sets = new ArrayList<>();

		ArrayList< Item > everything = new ArrayList<>();
		for (Item item : hero.belongings.backpack){
			if (item != null && Recipe.usableInRecipe( item )) everything.add( item );
		}

		if (everything.isEmpty()) return sets;

		sets.add( everything );

		if (everything.size() > 1 ) sets.add( new ArrayList<>( everything.subList( 0, 1 )));
		if (everything.size() > 2 ) sets.add( new ArrayList<>( everything.subList( 0, 2 )));
		if (everything.size() > MAX_INPUTS ) sets.add( new ArrayList<>( everything.subList( 0, MAX_INPUTS )));

		for (Item item : everything){
			if (item instanceof LiquidMetal || item instanceof MissileWeapon) continue;
			for (int copies = 2; copies <= MAX_INPUTS && copies <= item.quantity(); copies++) sets.add( new ArrayList<>( Collections.nCopies( copies, item )));
		}

		Set< List< Item > > offered = new HashSet<>( sets );
		for (int size = 1; size <= MAX_INPUTS; size++) addCombinations( everything, 0, size, new ArrayList<>(), sets, offered );

		return sets;
	}

	private static void addCombinations( List< Item > items, int from, int size, ArrayList< Item > chosen,
			List< ArrayList< Item > > sets, Set< List< Item > > offered ){
		if (chosen.size() == size){
			ArrayList< Item > set = new ArrayList<>( chosen );
			if (offered.add( set )) sets.add( set );
			return;
		}
		for (int i = from; i < items.size(); i++){
			Item item = items.get( i );
			if (Collections.frequency( chosen, item ) >= slotsFor( item )) continue;
			chosen.add( item );
			addCombinations( items, i, size, chosen, sets, offered );
			chosen.remove( chosen.size() - 1 );
		}
	}

	private static int slotsFor( Item item ){
		return item instanceof LiquidMetal || item instanceof MissileWeapon ? 1 : item.quantity();
	}


	public static List< Recipe > recipes( Hero hero ){
		List< Recipe > out = new ArrayList<>();

		for (ArrayList< Item > ingredients : ingredientSets( hero )){
			for (Recipe recipe : Recipe.findRecipes( new ArrayList<>( ingredients ))){
				if (recipe.cost( new ArrayList<>( ingredients )) > Dungeon.energy ) continue;
				if (!out.contains( recipe )) out.add( recipe );
			}
		}
		return out;
	}


	public static boolean isMeatPie( Recipe recipe ){
		return recipe instanceof MeatPie.Recipe;
	}


	public static boolean craft( Hero hero, int index ){
		List< Recipe > available = recipes( hero );
		if (index < 0 || index >= available.size()) return false;

		Recipe recipe = available.get( index );

		for (ArrayList< Item > ingredients : ingredientSets( hero )){
			if (!Recipe.findRecipes( new ArrayList<>( ingredients )).contains( recipe )) continue;

			int cost = recipe.cost( new ArrayList<>( ingredients ));
			if (cost > Dungeon.energy ) continue;
			Dungeon.energy -= cost;

			Item result = HeadlessGame.withParkedWindows( () -> recipe.brew( ingredients ));
			for (Item item : ingredients){
				if (item.quantity() <= 0) item.detachAll( hero.belongings.backpack );
			}
			if (result == null){
				if (!com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.open()){
					hero.next();
					return false;
				}
				hero.next();
				return true;
			}

			int quantity = result.quantity();
			if (!result.collect()){
				Dungeon.level.drop( result, hero.pos );
				hero.next();
				return false;
			}
			Statistics.itemsCrafted++;
			result.quantity( quantity );

			hero.next();
			return true;
		}
		return false;
	}


	static boolean adjacent( Hero hero, int cell ){
		return Dungeon.level != null && cell >= 0 && cell < Dungeon.level.length()
				&& Dungeon.level.adjacent( hero.pos, cell );
	}

}