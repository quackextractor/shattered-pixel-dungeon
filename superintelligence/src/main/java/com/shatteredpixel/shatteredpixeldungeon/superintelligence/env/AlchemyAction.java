package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MeatPie;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;

import java.util.ArrayList;
import java.util.List;

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

		return sets;
	}


	public static List< Recipe > recipes( Hero hero ){
		List< Recipe > out = new ArrayList<>();
		if (Dungeon.energy <= 0f) return out;

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

			ArrayList< Item > working = detach( hero, ingredients );
			if (working == null) return false;

			int cost = recipe.cost( new ArrayList<>( working ));
			if (cost > Dungeon.energy ) return false;
			Dungeon.energy -= cost;

			Item result = recipe.brew( working );
			if (result == null) return false;

			int quantity = result.quantity();
			if (!result.collect()) Dungeon.level.drop( result, hero.pos );
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


	private static ArrayList< Item > detach( Hero hero, List< Item > ingredients ){
		ArrayList< Item > working = new ArrayList<>();
		for (Item item : ingredients){
			Item detached = item.detach( hero.belongings.backpack );
			if (detached == null) return null;
			working.add( detached );
		}
		return working;
	}
}