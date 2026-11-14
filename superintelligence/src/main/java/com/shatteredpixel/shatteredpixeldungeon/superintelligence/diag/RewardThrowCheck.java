package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

final class RewardThrowCheck {

	private RewardThrowCheck(){}

	static void checkThrowingAndPickingUpIsNotProfitable( EnvConfig config ){
		RewardFixtures.prepare();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "THROWCHECK", HeroClass.WARRIOR );
		new Food().collect();
		env.step( Action.WAIT, 0 );
		int target = -1;
		int cell = -1;
		for (int o = 1; o < ActionMapper.ADJACENT_COUNT && target < 0; o++){
			int c = env.mapper().targetCell( o );
			if (c >= 0 && Dungeon.level.passable[ c ] && !Dungeon.level.avoid[ c ] && Dungeon.level.heaps.get( c ) == null
					&& Actor.findChar( c ) == null){
				target = o;
				cell = c;
			}
		}
		if (target < 0){
			fail( "no free cell beside the hero on THROWCHECK, so the throw round trip measured nothing" );
			return;
		}
		double dropped = env.ledger().termTotal( RewardTerm.ITEM_DROPPED );
		double paid = env.ledger().termTotal( RewardTerm.ITEM_PICKUP );
		env.step( Action.THROW, 0 );
		int slot = -1;
		for (int i = 0; i < env.mapper().slots().size() && slot < 0; i++) if (env.mapper().slots().get( i ) instanceof Food) slot = i;
		int carried = rations();
		if (env.mode() != EnvMode.SLOT || slot < 0){
			fail( "THROW with a ration carried reached " + env.mode() + " with the ration at slot " + slot );
			return;
		}
		env.step( Action.THROW, slot );
		env.step( Action.USE, target );
		Heap heap = Dungeon.level.heaps.get( cell );
		if (heap == null || !(heap.peek() instanceof Food) || rations() != carried - 1){
			fail( "the thrown ration did not land on the cell beside the hero, so there was nothing to pick up" );
			return;
		}
		for (int i = 0; i < 3 && env.running() && rations() < carried; i++){
			env.step( Action.INTERACT, env.mode() == EnvMode.WORLD ? target : 0 );
		}
		if (rations() < carried){
			fail( "the hero did not pick the thrown ration back up, so the round trip measured nothing" );
			return;
		}
		double charged = env.ledger().termTotal( RewardTerm.ITEM_DROPPED ) - dropped;
		double refunded = env.ledger().termTotal( RewardTerm.ITEM_PICKUP ) - paid;
		if (refunded + charged > 1e-4){
			fail( "throwing a ration beside the hero charged " + charged + " and picking it back up paid " + refunded
					+ ", so THROW then INTERACT is worth " + (refunded + charged) + " every few turns" );
			return;
		}
		System.out.println( "  a thrown item picked back up is not worth anything: " + charged + " charged, "
				+ refunded + " paid" );
	}

	private static int rations(){
		int n = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : Dungeon.hero.belongings) if (item instanceof Food) n += item.quantity();
		return n;
	}
}
