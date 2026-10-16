package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;
import java.util.List;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.adjacentEnemy;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.directionToTarget;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.placeMobBesideHero;
public final class RewardCombatCheck {
	private static final int MAX_HEAP_TRIES = 12;
	private RewardCombatCheck(){}
	public static void checkKillingIsWorthDoing( EnvConfig config ){
		final boolean[] placed = { false };
		final int[] lastMobHp = { -1 };
		Outcome o = play( config, 200, "KILLCHECK", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}
			if (Dungeon.hero != null && Dungeon.hero.isAlive() && Dungeon.hero.HP < 12){
				Dungeon.hero.HP = Dungeon.hero.HT;
			}
			if (!placed[ 0 ] && placeMobBesideHero( env )) placed[ 0 ] = true;
			Mob target = adjacentEnemy();
			if (target == null){
				env.step( Action.SEARCH, 0 );
				return;
			}
			lastMobHp[ 0 ] = target.HP;
			env.step( directionToTarget( env, target ), 0 );
		} );
		int kills = o.notes( RewardTerm.KILL );
		if (!placed[ 0 ]){
			fail( "no free cell beside the hero, so no mob could be placed and neither the KILL nor the"
					+ " SNEAK_ATTACK emission site was reached. A case that cannot reach the code it"
					+ " guards is not a check." );
			return;
		}
		if (kills == 0 ){
			fail( "a level-appropriate mob was placed beside the hero and fought for " + o.turns
					+ " turns with no kill recorded. The hero's melee went through Hero.handle exactly as"
					+ " it does for a player, so either Statistics.enemiesSlain is not moving or"
					+ " Mob.destroy is not reached headlessly - and KILL is diffed from that counter, so"
					+ " a KILL that never fires is a term worth nothing. Mob left at hp " + lastMobHp[ 0 ]
					+ ", damage taken " + o.termTotal( RewardTerm.DAMAGE_TAKEN ) );
			return;
		}
		if (o.termTotal( RewardTerm.KILL ) <= 0 ){
			fail( "a mob was killed and KILL paid " + o.termTotal( RewardTerm.KILL ) + ". The term and"
					+ " RewardLedger.countKill() both existed and neither was ever called, so fighting"
					+ " was worth nothing while being hit cost something." );
			return;
		}
		final int HP = 20;
		double wholeHealthBar = config.damagePenalty * HP;
		if (!( wholeHealthBar > config.deathPenalty / 10f )){
			fail( "losing a " + HP + " HP health bar costs " + wholeHealthBar + " against a death penalty"
					+ " of " + config.deathPenalty + ". Below about a tenth, an agent that dies every"
					+ " episode gets the same terminal reward every episode and the only signal that"
					+ " varies is a fraction of it - so there is no gradient to learn from at all."
					+ " Measured: two runs at 0.25/HP both sat at 100% deaths." );
			return;
		}
		if (!( config.damagePenalty * 8 < config.killReward * 4 )){
			fail( "killing a mob worth about 8 HP costs " + ( config.damagePenalty * 8 )
					+ " in damage against " + config.killReward + " paid, so the agent would rather walk"
					+ " past than fight - which is the state KILL being dead for in the first place." );
			return;
		}
		if (!( config.sneakAttackReward > config.killReward )){
			fail( "a surprise attack pays " + config.sneakAttackReward + " and a kill pays "
					+ config.killReward + ". The surprise is the one that costs no health -"
					+ " Mob.surprisedBy skips the defender's defence - so paying it less than the kill"
					+ " teaches trading blows instead." );
			return;
		}
		int sneaks = o.notes( RewardTerm.SNEAK_ATTACK );
		if (sneaks == 0 ){
			fail( "the fight killed " + kills + " mob(s) and recorded no SNEAK_ATTACK, on a mob the"
					+ " level had just produced and therefore had not seen the hero. The term is meant"
					+ " to pay for arriving unseen - Mob.surprisedBy skips the defender's defence - and a"
					+ " counter that never moves is not a reward." );
			return;
		}
		if (o.termTotal( RewardTerm.SNEAK_ATTACK ) <= 0 ){
			fail( "SNEAK_ATTACK was recorded " + sneaks + " time(s) and paid "
					+ o.termTotal( RewardTerm.SNEAK_ATTACK ) + "." );
			return;
		}
		System.out.println( "  killing pays " + fmt( config.killReward ) + " and a surprise attack "
				+ fmt( config.sneakAttackReward ) + "; " + kills + " kill(s) and " + sneaks
				+ " surprise(s) recorded" );
	}
	public static void checkPickingUpAndDroppingIsNotProfitable( EnvConfig config ){
		final int[] phase = { 0 };
		final int[] tries = { 0 };
		final Item[] grabbed = { new Potion() };
		final double[] paid = { 0 };
		final double[] refunded = { 0 };
		final int[] slotUsed = { -1 };
		Outcome o = play( config, 200, "EXPLOITCHECK", turn -> {
			SPDEnv env = turn.env;
			EnvMode mode = env.mode();
			switch ( phase[0] ) {
				case 0:
					if (mode != EnvMode.WORLD){ env.step( Action.CANCEL, 0 ); return; }
					if (tries[ 0 ] >= MAX_HEAP_TRIES){
						phase[0] = 9;
						return;
					}
					tries[0]++;
					grabbed[ 0 ] = new Potion();
					grabbed[ 0 ].identify();
					if (!takeItemFromTheFloor( env, grabbed[ 0 ] )) return;
					if (!carries( grabbed[ 0 ] )){
						phase[0] = 0;
						return;
					}
					paid[ 0 ] = env.ledger().termTotal( RewardTerm.ITEM_PICKUP );
					phase[ 0 ] = 1;
					return;
				case 1:
					if (mode == EnvMode.WORLD){ env.step( Action.OPEN_INVENTORY, 0 ); return; }
					if (mode == EnvMode.INVENTORY){
						int slot = slotOf( env, grabbed[ 0 ] );
						slotUsed[ 0 ] = slot;
						env.step( slot < 0 ? Action.CANCEL : Action.DROP, Math.max( slot, 0 ));
						refunded[ 0 ] = env.ledger().termTotal( RewardTerm.ITEM_DROPPED );
					} else {
						env.step( Action.CANCEL, 0 );
					}
					phase[ 0 ] = 2;
					return;
				default:
					if (mode != EnvMode.WORLD) env.step( Action.CANCEL, 0 );
					else env.step( Action.WAIT, 0 );
			}
		} );
		if (phase[ 0 ] == 9 ){
			fail( "the hero would not take an item off the floor across " + MAX_HEAP_TRIES
					+ " attempts, so this case measured nothing." );
			return;
		}
		if (grabbed[ 0 ] == null ){
			fail( "the hero walked onto a heap carrying a known item and did not pick it up, so the"
					+ " pickup half of this case was not exercised." );
			return;
		}
		if (paid[ 0 ] <= 0 ){
			fail( "picking up " + grabbed[ 0 ].getClass().getSimpleName() + " paid " + paid[ 0 ]
					+ " of ITEM_PICKUP, so there was nothing for the drop to refund and this case"
					+ " passed without measuring the exploit." );
			return;
		}
		if (slotUsed[ 0 ] < 0 ){
			fail( "the item that was picked up is not in any slot, so it could not be dropped."
					+ " ActionMapper.refreshSlots covers the backpack and nothing else." );
			return;
		}
		if (Math.abs( refunded[ 0 ] + paid[ 0 ] ) > 1e-4 ){
			fail( "picking an item up paid " + paid[ 0 ] + " and putting it back down refunded "
					+ refunded[ 0 ] + ", so the round trip is worth " + ( paid[ 0 ] + refunded[ 0 ] )
					+ ". The pickup term compared a count one way and charged nothing for the loss, which"
					+ " is a free score every three steps - issues.md 8." );
			return;
		}
		double identification = o.termTotal( RewardTerm.IDENTIFIED );
		if (Math.abs( identification ) > 1e-4 ){
			fail( "the pickup/drop round trip also moved IDENTIFIED by " + identification
					+ ", so it was not free even though ITEM_PICKUP and ITEM_DROPPED cancelled. IDENTIFIED"
					+ " compared a count one way and refunded nothing on a decrease, which makes"
					+ " DROP-then-INTERACT a repeatable +0.5 on the same heap. Measured on a trainer run:"
					+ " IDENTIFIED +27.50 against an inventory round trip that was net -5.00." );
			return;
		}
		System.out.println( "  a pickup and its matching drop cancel: " + fmt( paid[ 0 ] ) + " paid,"
				+ " " + fmt( refunded[ 0 ] ) + " refunded, net " + fmt( paid[ 0 ] + refunded[ 0 ] )
				+ ", and no other term pays for the round trip" );
	}
	public static void checkSpendingATalentIsWorthALot( EnvConfig config ){
		if (!( config.depthReward > config.talentReward )){
			fail( "a talent point pays " + config.talentReward + " against " + config.depthReward
					+ " for a descent. Depth is the objective, so it must outbid every incidental term -"
					+ " otherwise the agent levelling is the best strategy on the board and descending is"
					+ " not. This gate once required the opposite, which is how a talent point reached 4.7"
					+ " floors while a descent was worth 1.2." );
			return;
		}
		final int[] spent = { 0 };
		Outcome o = play( config, 40, "TALENTCHECK", turn -> {
			Hero hero = Dungeon.hero;
			if (hero == null || !hero.isAlive()) return;
			if (turn.step < 2){
				turn.env.step( Action.WAIT, 0 );
				return;
			}
			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent talent = firstTalent( hero );
			if (talent == null){
				turn.env.step( Action.WAIT, 0 );
				return;
			}
			hero.upgradeTalent( talent );
			spent[ 0 ]++;
			turn.env.step( Action.WAIT, 0 );
		} );
		int notes = o.notes( RewardTerm.TALENT_UPGRADE );
		if (spent[ 0 ] == 0 ){
			fail( "the driver could not find a talent to spend on this hero, so the emission site was"
					+ " never reached. Hero.talents is what the game itself reads, so an empty one means"
					+ " the hero was never initialised - a fault this case is supposed to detect rather"
					+ " than be confused by." );
			return;
		}
		if (notes == 0 ){
			fail( spent[ 0 ] + " talent point(s) were spent and TALENT_UPGRADE was never recorded."
					+ " Action.TALENT and EnvMode.TALENT exist, so the emission site is supposed to be"
					+ " live. This is the shape KILL had: declared, plumbed, and never emitted." );
			return;
		}
		double paid = o.termTotal( RewardTerm.TALENT_UPGRADE );
		if (paid <= 0 ){
			fail( "TALENT_UPGRADE was recorded " + notes + " time(s) and paid " + paid + "." );
			return;
		}
		double levelUp = 2f;
		if (!( paid > levelUp * notes )){
			fail( "a talent point pays " + ( paid / notes ) + " against " + levelUp + " for a level."
					+ " Levelling raises attack, defence and max HP by one each and happens whether the"
					+ " agent plays well or not; a point is a permanent choice between options that"
					+ " differ by an order of magnitude. Paying them alike says the choice does not"
					+ " matter." );
			return;
		}
		System.out.println( "  a talent point pays " + fmt( config.talentReward ) + " against "
				+ fmt( levelUp ) + " for a level and " + fmt( config.depthReward )
				+ " for a descent - worth levelling for, and less than going deeper; "
				+ notes + " point(s) recorded" );
	}
	public static void checkRemovingACursePays( EnvConfig config ){
		final Item[] cursed = { null };
		final int[] removedAt = { -1 };
		Outcome o = play( config, 120, "CURSEOFF", turn -> {
			SPDEnv env = turn.env;
			Hero hero = Dungeon.hero;
			if (hero == null || !hero.isAlive()) return;
			if (cursed[ 0 ] == null){
				cursed[ 0 ] = new com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword();
				cursed[ 0 ].cursed = true;
				cursed[ 0 ].cursedKnown = true;
				cursed[ 0 ].identify();
				cursed[ 0 ].collect( hero.belongings.backpack );
				env.step( Action.WAIT, 0 );
				return;
			}
			if (removedAt[ 0 ] < 0){
				double before = env.ledger().termTotal( RewardTerm.CURSE_REMOVED );
				cursed[ 0 ].cursed = false;
				env.step( Action.WAIT, 0 );
				double paid = env.ledger().termTotal( RewardTerm.CURSE_REMOVED ) - before;
				if (paid != 0) removedAt[ 0 ] = turn.step;
			} else {
				env.step( Action.WAIT, 0 );
			}
		} );
		if (cursed[ 0 ] == null){
			fail( "the hero was never built, so no cursed item could be carried and this case"
					+ " measured nothing" );
			return;
		}
		if (removedAt[ 0 ] < 0){
			fail( "a carried item stopped being cursed and CURSE_REMOVED was recorded "
					+ o.notes( RewardTerm.CURSE_REMOVED ) + " time(s) paying "
					+ o.termTotal( RewardTerm.CURSE_REMOVED ) + ". docs.md asks for removing a curse to"
					+ " pay, and this is the state that has to move for it to." );
			return;
		}
		System.out.println( "  removing a curse pays " + fmt( o.termTotal( RewardTerm.CURSE_REMOVED ) )
				+ ", recorded at step " + removedAt[ 0 ] );
	}
	public static void checkWearingSomethingTooStrongIsPaidFor( EnvConfig config ){
		final com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor[] heavy = { null };
		final boolean[] worn = { false };
		final double[] paid = { 0 };
		Outcome o = play( config, 120, "ENCUMBER", turn -> {
			SPDEnv env = turn.env;
			Hero hero = Dungeon.hero;
			if (hero == null || !hero.isAlive()) return;
			if (!worn[ 0 ]){
				heavy[ 0 ] = new com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor( 5 );
				env.step( Action.WAIT, 0 );
				double before = env.ledger().termTotal( RewardTerm.EQUIP_TOO_STRONG );
				hero.belongings.armor = heavy[ 0 ];
				worn[ 0 ] = true;
				env.step( Action.WAIT, 0 );
				paid[ 0 ] = env.ledger().termTotal( RewardTerm.EQUIP_TOO_STRONG ) - before;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );
		if (heavy[ 0 ] == null || !worn[ 0 ]){
			fail( "the fixture never equipped anything, so the encumbrance state it exists to reach was"
					+ " never created and this case measured nothing" );
			return;
		}
		if (heroStrength() >= heavy[ 0 ].STRReq()){
			fail( "the hero's strength is " + heroStrength() + " against a requirement of "
					+ heavy[ 0 ].STRReq() + ", so the item was not too strong to wear and this case"
					+ " measured nothing. A stronger hero, or a higher tier, would do it." );
			return;
		}
		if (o.termTotal( RewardTerm.EQUIP_TOO_STRONG ) >= 0 ){
			fail( "equipping an item the hero is too weak to wear paid "
					+ o.termTotal( RewardTerm.EQUIP_TOO_STRONG ) + " (step " + (paid[ 0 ] ) + "). The"
					+ " game does not refuse the equip - STRReq is an encumbrance and Hero.drRoll"
					+ " charges two points per point of shortfall - so this is the state to punish."
					+ " docs.md lists it under what the agent should be punished for." );
			return;
		}
		System.out.println( "  wearing something too strong pays "
				+ fmt( o.termTotal( RewardTerm.EQUIP_TOO_STRONG ) ) + " (hero strength "
				+ heroStrength() + ", requirement " + heavy[ 0 ].STRReq() + ")" );
	}
	private static int heroStrength(){
		return Dungeon.hero == null ? 0 : Dungeon.hero.STR();
	}
	private static int emptySlot( SPDEnv env ){
		List< Item > slots = env.mapper().slots();
		for (int i = slots.size() - 1; i >= 0; i--){
			if (slots.get( i ) == null) return i;
		}
		return 0;
	}
	public static void checkAMenuNoOpIsUnreachable( EnvConfig config ){
		final int[] inSubMode = { 0 };
		final int[] advanced = { 0 };
		final int[] leftTheMode = { 0 };
		Outcome o = play( config, 200, "MENUNOOP", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD){
				EnvMode before = env.mode();
				float clock = Actor.now();
				inSubMode[ 0 ]++;
				env.step( Action.USE, emptySlot( env ));
				if (Actor.now() > clock) advanced[ 0 ]++;
				else if (env.mode() != before) leftTheMode[ 0 ]++;
				return;
			}
			env.step( Action.USE, 0 );
		} );
		if (inSubMode[ 0 ] == 0){
			fail( "no step was issued from a sub-mode over " + o.turns + " turns, so this case"
					+ " measured nothing" );
			return;
		}
		int noOps = inSubMode[ 0 ] - advanced[ 0 ] - leftTheMode[ 0 ];
		if (noOps > 0 ){
			fail( noOps + " of " + inSubMode[ 0 ] + " sub-mode steps changed neither the engine clock"
					+ " nor the mode (" + advanced[ 0 ] + " advanced, " + leftTheMode[ 0 ]
					+ " left the mode). A menu no-op now exists, so MENU_NOOP has a reachable state and"
					+ " its emission site can be written." );
			return;
		}
		System.out.println( "  a sub-mode action always either advances the clock (" + advanced[ 0 ]
				+ ") or leaves the mode (" + leftTheMode[ 0 ] + ") across " + inSubMode[ 0 ]
				+ " steps, so MENU_NOOP has no reachable state yet" );
	}
	private static com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent firstTalent( Hero hero ){
		if (hero.talents.isEmpty()) return null;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent t : hero.talents.get( 0 ).keySet()){
			return t;
		}
		return null;
	}
	private static boolean takeItemFromTheFloor( SPDEnv env, Item item ){
		if (Dungeon.level == null || Dungeon.hero == null) return false;
		env.step( Action.WAIT, 0 );
		if (!env.running()) return false;
		int w = Dungeon.level.width();
		int[] offsets = { -w, w, -1, 1, -w - 1, -w + 1, w - 1, w + 1 };
		for (int off : offsets){
			int cell = Dungeon.hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell ) || !Dungeon.level.passable[ cell ]) continue;
			if (Math.abs( cell % w - Dungeon.hero.pos % w ) > 1) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;
			Dungeon.level.drop( item, cell );
			Action walk = directionTo( Dungeon.hero.pos, cell );
			if (walk == null) continue;
			env.step( walk, 0 );
			if (!env.running()) return false;
			if (carries( item )) return true;
		}
		return false;
	}
	private static boolean carries( Item item ){
		for (Item carried : Dungeon.hero.belongings){
			if (carried == item) return true;
		}
		return false;
	}
	private static Action directionTo( int from, int to ){
		int w = Dungeon.level.width();
		int dx = Integer.compare( to % w, from % w );
		int dy = Integer.compare( to / w, from / w );
		for (Action a : Action.values()){
			if (a.directional() && Integer.compare( a.dx, 0 ) == dx && Integer.compare( a.dy, 0 ) == dy){
				return a;
			}
		}
		return null;
	}
	private static int slotOf( SPDEnv env, Item item ){
		if (item == null) return -1;
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (slots.get( i ) == item) return i;
		}
		return -1;
	}
}
