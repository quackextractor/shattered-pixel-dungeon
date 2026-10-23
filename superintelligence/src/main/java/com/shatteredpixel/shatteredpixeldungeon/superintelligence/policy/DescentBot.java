package com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Travel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.PathFinder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

public final class DescentBot {

	private int lastHp = -1;

	private int waited = 0;

	private final java.util.Map< Integer, java.util.BitSet > searched = new java.util.HashMap<>();

	private final java.util.Set< Item > tried = java.util.Collections.newSetFromMap( new java.util.IdentityHashMap<>() );

	public void reset(){
		lastHp = -1;
		waited = 0;
		searched.clear();
		tried.clear();
	}

	public boolean fresh(){
		return lastHp < 0 && searched.isEmpty();
	}

	public Action act( SPDEnv env, int[] secondary ){
		secondary[ 0 ] = 0;
		float[] mask = env.actionMask();
		switch (env.mode()){
			case SLOT:
			case INVENTORY:
				int pick = GameScene.answerableSelector() != null ? target( env ) : useSlot( env );
				if (pick < 0) return Action.CANCEL;
				if (GameScene.answerableSelector() == null) tried.add( env.mapper().slot( pick ) );
				secondary[ 0 ] = pick;
				return Action.USE;
			case TALENT:
				secondary[ 0 ] = firstLegal( env.slotMask() );
				return Action.MENU_SELECT;
			case MENU:
				return legal( mask, Action.MENU_SELECT ) ? Action.MENU_SELECT : Action.CANCEL;
			case WORLD:
				return world( env, mask );
			default:
				return Action.CANCEL;
		}
	}

	public int label( SPDEnv env, int liveHead ){
		int[] secondary = new int[ 1 ];
		Action action = act( env, secondary );
		if (liveHead == Policy.HEAD_ACTION) return legal( env.actionMask(), action ) ? action.index : -1;
		if (liveHead == Policy.HEAD_SLOT){
			int s = secondary[ 0 ];
			return s >= 0 && s < env.slotMask().length && env.slotMask()[ s ] > 0.5f && action != Action.CANCEL ? s : -1;
		}
		return -1;
	}

	private Action world( SPDEnv env, float[] mask ){
		Hero hero = Dungeon.hero;
		boolean hurt = lastHp >= 0 && hero.HP < lastHp;
		lastHp = hero.HP;
		if (dying() && !hero.getVisibleEnemies().isEmpty() && healSlot( env ) >= 0 && legal( mask, Action.USE )) return Action.USE;
		Action attack = attack( mask );
		if (attack != null) return attack;
		Action escape = escape( mask );
		if (escape != null) return escape;
		if (approaching() && waited < 2 && legal( mask, Action.WAIT )){
			waited++;
			return Action.WAIT;
		}
		waited = 0;
		Hunger hunger = hero.buff( Hunger.class );
		if (hunger != null && hunger.hunger() >= Hunger.HUNGRY && foodSlot( env ) >= 0 && legal( mask, Action.USE )){
			return Action.USE;
		}
		if (hero.getVisibleEnemies().isEmpty() && improveSlot( env ) >= 0 && legal( mask, Action.USE )) return Action.USE;
		boolean famished = hunger != null && hunger.hunger() >= Hunger.HUNGRY;
		if (!hurt && !famished && hero.HP < hero.HT && hero.getVisibleEnemies().isEmpty() && legal( mask, Action.REST )) return Action.REST;
		if (!famished && legal( mask, Action.TRAVEL_EXPLORE )) return Action.TRAVEL_EXPLORE;
		if (legal( mask, Action.TRAVEL_STAIRS )) return Action.TRAVEL_STAIRS;
		if (legal( mask, Action.TRAVEL_EXPLORE )) return Action.TRAVEL_EXPLORE;
		return hunt( mask );
	}

	private Action hunt( float[] mask ){
		Level level = Dungeon.level;
		int hero = Dungeon.hero.pos;
		java.util.BitSet done = searched.computeIfAbsent( Dungeon.depth * 64 + Dungeon.branch, k -> new java.util.BitSet() );
		int target = searchSpot( level, hero, done, true );
		if (target < 0) target = searchSpot( level, hero, done, false );
		if (target == hero || target < 0){
			for (int d : PathFinder.NEIGHBOURS8) if (hero + d >= 0 && hero + d < level.length()) done.set( hero + d );
			return legal( mask, Action.SEARCH ) ? Action.SEARCH : Action.WAIT;
		}
		Action move = toward( mask, hero, PathFinder.getStep( hero, target, Travel.passThrough( level ) ) );
		if (move != null) return move;
		return legal( mask, Action.SEARCH ) ? Action.SEARCH : Action.WAIT;
	}

	private static int searchSpot( Level level, int hero, java.util.BitSet done, boolean nearUnknown ){
		PathFinder.buildDistanceMap( hero, Travel.passThrough( level ) );
		int best = -1;
		for (int c = 0; c < level.length(); c++){
			if (PathFinder.distance[ c ] == Integer.MAX_VALUE) continue;
			if (best >= 0 && PathFinder.distance[ c ] >= PathFinder.distance[ best ]) continue;
			for (int d : PathFinder.NEIGHBOURS8){
				int w = c + d;
				if (w < 0 || w >= level.length() || done.get( w ) || level.passable[ w ] || !Travel.known( level, w )) continue;
				if (!nearUnknown || bordersUnknown( level, w )){
					best = c;
					break;
				}
			}
		}
		return best;
	}

	private static boolean bordersUnknown( Level level, int cell ){
		for (int d : PathFinder.NEIGHBOURS8){
			int n = cell + d;
			if (n >= 0 && n < level.length() && !Travel.known( level, n )) return true;
		}
		return false;
	}

	private static Action escape( float[] mask ){
		Level level = Dungeon.level;
		int hero = Dungeon.hero.pos;
		boolean[] safe = Travel.passThrough( level );
		boolean near = Travel.harmful( level, hero );
		for (int d : PathFinder.NEIGHBOURS8) near |= hero + d >= 0 && hero + d < level.length() && Travel.harmful( level, hero + d );
		if (!near) return null;
		boolean[] open = new boolean[ level.length() ];
		for (int c = 0; c < open.length; c++) open[ c ] = level.passable[ c ] && Travel.known( level, c );
		PathFinder.buildDistanceMap( hero, open );
		int best = -1;
		for (int c = 0; c < open.length; c++){
			if (c == hero || !safe[ c ] || PathFinder.distance[ c ] == Integer.MAX_VALUE) continue;
			if (best < 0 || PathFinder.distance[ c ] < PathFinder.distance[ best ]) best = c;
		}
		return best < 0 ? null : toward( mask, hero, PathFinder.getStep( hero, best, open ) );
	}

	private static Action toward( float[] mask, int hero, int step ){
		int width = Dungeon.level.width();
		for (Action a : Action.values()){
			if (a.directional() && step >= 0 && hero + a.dx + a.dy * width == step && legal( mask, a )) return a;
		}
		return null;
	}

	private static boolean approaching(){
		int hero = Dungeon.hero.pos;
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && mob.state == mob.HUNTING && Dungeon.level.distance( mob.pos, hero ) == 2) return true;
		}
		return false;
	}

	private static Action attack( float[] mask ){
		int width = Dungeon.level.width();
		int hero = Dungeon.hero.pos;
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (!mob.isAlive() || !Dungeon.level.adjacent( mob.pos, hero )) continue;
			int dx = mob.pos % width - hero % width;
			int dy = mob.pos / width - hero / width;
			for (Action a : Action.values()){
				if (a.directional() && a.dx == dx && a.dy == dy && legal( mask, a )) return a;
			}
		}
		return null;
	}

	private static boolean dying(){
		return Dungeon.hero.HP * 3 < Dungeon.hero.HT;
	}

	private static int healSlot( SPDEnv env ){
		float[] mask = env.slotMask();
		int fallback = -1;
		for (int i = 0; i < mask.length; i++){
			Item item = env.mapper().slot( i );
			if (mask[ i ] < 0.5f || item == null) continue;
			if (item instanceof PotionOfHealing && ((Potion) item).isKnown()) return i;
			if (item instanceof Waterskin && !((Waterskin) item).isEmpty()) fallback = i;
			else if (fallback < 0 && item instanceof Potion && !((Potion) item).isKnown()) fallback = i;
		}
		return fallback;
	}

	private int useSlot( SPDEnv env ){
		int pick = dying() ? healSlot( env ) : -1;
		if (pick < 0) pick = improveSlot( env );
		if (pick < 0) pick = foodSlot( env );
		return pick;
	}

	private int improveSlot( SPDEnv env ){
		float[] mask = env.slotMask();
		Hero hero = Dungeon.hero;
		for (int i = 0; i < mask.length; i++){
			Item item = env.mapper().slot( i );
			if (mask[ i ] < 0.5f || item == null || tried.contains( item )) continue;
			if (item instanceof PotionOfStrength && item.isIdentified()) return i;
			if (item instanceof ScrollOfUpgrade || (item instanceof Scroll && !((Scroll) item).isKnown())) return i;
			if (item instanceof Armor && betterArmor( (Armor) item, hero )) return i;
		}
		return -1;
	}

	private static boolean betterArmor( Armor armor, Hero hero ){
		Armor worn = hero.belongings.armor();
		if (armor.cursedKnown && armor.cursed) return false;
		if (armor.STRReq() > hero.STR()) return false;
		return worn == null || armor.tier > worn.tier;
	}

	private static int target( SPDEnv env ){
		float[] mask = env.slotMask();
		Hero hero = Dungeon.hero;
		int first = -1;
		for (Item want : new Item[]{ hero.belongings.armor(), hero.belongings.weapon() }){
			for (int i = 0; i < mask.length; i++){
				if (mask[ i ] < 0.5f) continue;
				if (first < 0) first = i;
				if (want != null && env.mapper().slot( i ) == want) return i;
			}
		}
		return first;
	}

	private static int foodSlot( SPDEnv env ){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			Item item = env.mapper().slot( i );
			if (mask[ i ] > 0.5f && item instanceof Food) return i;
		}
		return -1;
	}

	private static int firstLegal( float[] mask ){
		for (int i = 0; i < mask.length; i++) if (mask[ i ] > 0.5f) return i;
		return 0;
	}

	private static boolean legal( float[] mask, Action action ){
		return action.index < mask.length && mask[ action.index ] > 0.5f;
	}
}
