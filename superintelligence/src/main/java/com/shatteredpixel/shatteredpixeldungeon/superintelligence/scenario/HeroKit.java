package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;

public final class HeroKit {

	public Hero hero(){
		return Dungeon.hero;
	}

	public HeroKit health( int hp, int ht ){
		hero().HT = ht;
		hero().HP = hp;
		return this;
	}

	public HeroKit sturdy(){
		return health( 1000, 1000 );
	}

	public < T extends Item > T give( T item ){
		item.identify();
		if (!item.collect( hero().belongings.backpack )) throw new NoFixture( "the pack refused " + item.name() );
		return item;
	}

	public < T extends Item > T giveUnknown( T item ){
		if (!item.collect( hero().belongings.backpack )) throw new NoFixture( "the pack refused " + item.name() );
		return item;
	}

	public < T extends Buff > T buff( Class< T > type ){
		return Buff.affect( hero(), type );
	}

	public < T extends FlavourBuff > T buff( Class< T > type, float duration ){
		return Buff.affect( hero(), type, duration );
	}

	public HeroKit gold( int gold ){
		Dungeon.gold = gold;
		return this;
	}

	public HeroKit energy( int energy ){
		Dungeon.energy = energy;
		return this;
	}

	public HeroKit level( int lvl ){
		hero().lvl = lvl;
		return this;
	}

	public HeroKit emptyPack(){
		hero().belongings.backpack.items.clear();
		Dungeon.quickslot.reset();
		return this;
	}

	public HeroKit strength( int str ){
		hero().STR = str;
		return this;
	}
}
