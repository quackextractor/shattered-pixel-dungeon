package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;

import java.util.ArrayList;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.adjacentEnemy;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.ascend;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.descend;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.directionToTarget;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.equippableSlot;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.playWaits;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.placeMobBesideHero;

public final class RewardDescentCheck {

	private RewardDescentCheck(){}

	public static void checkTheBossIsPaidForOnLeavingItsFloor( EnvConfig config ){
		final int[] onArrival = { -1 };

		Outcome o = play( config, 60, "BOSSFLOOR", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}
			if (Dungeon.depth == 5 && onArrival[ 0 ] < 0){
				onArrival[ 0 ] = env.ledger().notes( RewardTerm.BOSS_SLAIN );
			}
			if (Dungeon.depth < 6 && descend( env )){
				env.step( Action.WAIT, 0 );
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (onArrival[ 0 ] < 0 || o.deepest < 6){
			fail( "the driver reached depth " + o.deepest + " and not 6, so neither the arrival on the"
					+ " sewer boss floor nor the departure from it was measured." );
			return;
		}
		if (onArrival[ 0 ] != 0){
			fail( "arriving on depth 5 noted BOSS_SLAIN " + onArrival[ 0 ] + " time(s) before Goo was"
					+ " even seen. SPDEnv asked Dungeon.bossLevel() of the depth it had just arrived on." );
			return;
		}
		if (o.notes( RewardTerm.BOSS_SLAIN ) != 1){
			fail( "leaving depth 5 for depth 6 noted BOSS_SLAIN " + o.notes( RewardTerm.BOSS_SLAIN )
					+ " time(s), expected once. The game unlocks a boss floor's exit when the boss dies." );
			return;
		}

		System.out.println( "  BOSS_SLAIN is paid leaving depth 5 (" + fmt( o.termTotal( RewardTerm.BOSS_SLAIN ) )
				+ "), not arriving on it" );
	}


	public static void checkOnlyANewFloorIsPaidFor( EnvConfig config ){
		final int[] descents = { 0 };
		final int[] ascents = { 0 };

		Outcome o = play( config, 120, "PINGPONG", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) return;

			if (turn.step % 12 == 4 ){
				if (ascend( env )){
					ascents[ 0 ]++;
					return;
				}
			}
			if (turn.step % 12 == 0 ){
				if (descend( env )) descents[ 0 ]++;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (descents[ 0 ] < 3 || ascents[ 0 ] < 2 ){
			fail( "the driver performed " + descents[ 0 ] + " descent(s) and " + ascents[ 0 ] + " ascent(s),"
					+ " so it never reached the shape this case is about (depth "
					+ Dungeon.depth + ", deepest " + o.deepest + "). A case that cannot reach the code it"
					+ " guards is not a check." );
			return;
		}

		if (o.deepest < 2 ){
			fail( "the driver descended " + descents[ 0 ] + " times and never got past depth "
					+ o.deepest + ", so there was nothing to re-descend." );
			return;
		}

		double paid = o.termTotal( RewardTerm.DEPTH_ADVANCE );
		double oneFloor = config.depthReward * (o.deepest - 1);

		if (Math.abs( paid - oneFloor ) > 1e-4 ){
			fail( "after " + descents[ 0 ] + " descents to a deepest floor of " + o.deepest
					+ ", DEPTH_ADVANCE paid " + paid + " where " + oneFloor + " was expected - one reward"
					+ " per new floor, and none for coming back down a stair the run had already used."
					+ " Paying on the direction made 1 -> 2 -> 1 -> 2 a money machine: +7200 at deepest=2 on"
					+ " JEA-FKU-WZS." );
			return;
		}

		System.out.println( "  " + descents[ 0 ] + " descents and " + ascents[ 0 ] + " ascents reached depth "
				+ o.deepest + " and paid " + fmt( paid ) + " - one reward per new floor, not per stair" );
	}


	public static void checkDefaultProfilePaysEveryTerm(){
		List< String > refused = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (!RewardProfile.DEFAULT.allows( term )) refused.add( term.name() );
		}

		if (!refused.isEmpty()){
			fail( "the DEFAULT profile refuses " + refused + ", so the shipped reward function no longer"
					+ " pays for terms it is documented to pay for." );
			return;
		}

		RewardProfile compiled = new EnvConfig().rewardProfile;
		if (compiled != RewardProfile.DEFAULT ){
			fail( "a run with no configuration and no SPD_* variables asks for " + compiled
					+ ". The compiled default has to be DEFAULT or the experiment ships enabled." );
			return;
		}

		RewardProfile loaded = com.shatteredpixel.shatteredpixeldungeon.superintelligence.env
				.EnvConfigBinder.defaults().config.rewardProfile;
		if (loaded != RewardProfile.DEFAULT ){
			fail( "the binder loads " + loaded + " from nothing, where a run with no file must load"
					+ " DEFAULT." );
			return;
		}

		System.out.println( "  DEFAULT admits all " + RewardTerm.count() + " terms and is what a run with"
				+ " no configuration loads" );
	}


	public static void checkDepthOnlyPaysOnlyForDepth(){
		EnvConfig config = new EnvConfig();
		config.rewardProfile = RewardProfile.DEPTH_ONLY;

		final boolean[] placed = { false };
		final boolean[] descended = { false };
		final int[] incidental = { 0 };
		final List< String > incidentalTerms = new ArrayList<>();

		Outcome o = play( config, 220, "DEPTHONLY", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}

			if (Dungeon.hero != null && Dungeon.hero.isAlive() && Dungeon.hero.HP < 12){
				Dungeon.hero.HP = Dungeon.hero.HT;
			}

			if (turn.step % 4 == 3 ){
				env.step( Action.USE, 0 );
				if (env.mode() == EnvMode.SLOT) env.step( Action.USE, equippableSlot( env ) );
				return;
			}

			if (!descended[ 0 ] && turn.step >= 40 && descend( env )){
				descended[ 0 ] = true;
				return;
			}

			if (!placed[ 0 ] && placeMobBesideHero( env )) placed[ 0 ] = true;

			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob target = adjacentEnemy();
			if (target != null){
				env.step( directionToTarget( env, target ), 0 );
				return;
			}

			if (turn.step % 3 == 0) env.step( Action.SEARCH, 0 );
			else env.step( Action.values()[ 4 + (turn.step % 4 )], 0 );
		} );

		if (o.ledger == null || o.ledger.profile() != RewardProfile.DEPTH_ONLY ){
			fail( "the episode ran under " + ( o.ledger == null ? "no ledger" : o.ledger.profile() )
					+ " rather than depth_only, so nothing below measured the override." );
			return;
		}

		List< String > paid = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (RewardProfile.DEPTH_ONLY.allows( term )) continue;
			int notes = o.notes( term );
			if (notes > 0 ) incidentalTerms.add( term.name() + "x" + notes );
			if (o.termTotal( term ) != 0 ){
				paid.add( term.name() + "=" + o.termTotal( term ));
			}
			incidental[ 0 ] += notes;
		}

		if (!paid.isEmpty()){
			fail( "depth_only paid " + paid + ". The only route to a positive score under this profile is"
					+ " a new floor or a win; everything else must total exactly zero." );
			return;
		}

		if (o.notes( RewardTerm.EXPLORE ) == 0 ){
			fail( "the driver searched and walked for " + o.turns + " turns and EXPLORE was never recorded,"
					+ " so this case measured an episode that never reached the terms it silences." );
			return;
		}

		int combat = o.notes( RewardTerm.KILL ) + o.notes( RewardTerm.SNEAK_ATTACK )
				+ o.notes( RewardTerm.DAMAGE_TAKEN );
		if (combat == 0 ){
			fail( "a level-appropriate mob was placed beside the hero and fought for " + o.turns
					+ " turns with no KILL, SNEAK_ATTACK or DAMAGE_TAKEN recorded (placed=" + placed[ 0 ]
					+ "), so the combat terms were never exercised under the override." );
			return;
		}

		if (o.notes( RewardTerm.NO_PROGRESS ) == 0 ){
			fail( "the driver equipped and unequipped the hero's weapon throughout and NO_PROGRESS was never"
					+ " recorded, so the free-step charge was not exercised under the override." );
			return;
		}

		if (!descended[ 0 ] || Dungeon.depth <= 1 ){
			fail( "the hero never descended (depth " + Dungeon.depth + ", descended=" + descended[ 0 ]
					+ "), so the case never reached the one route to a positive score it exists to protect." );
			return;
		}

		double depthPaid = o.termTotal( RewardTerm.DEPTH_ADVANCE );
		if (depthPaid < config.depthReward ){
			fail( "the hero reached depth " + Dungeon.depth + " and DEPTH_ADVANCE paid " + depthPaid
					+ " against a configured " + config.depthReward + ". Under depth_only this term is the"
					+ " objective, and the one positive score an episode can earn." );
			return;
		}

		double positives = 0;
		for (int i = 0; i < RewardTerm.count(); i++){
			double total = o.termTotal( RewardTerm.at( i ));
			if (total > 0) positives += total;
		}
		if (Math.abs( positives - depthPaid ) > 1e-4 ){
			fail( "the run's positive score totals " + positives + " against " + depthPaid
					+ " of DEPTH_ADVANCE, so something other than the descent paid for something." );
			return;
		}

		System.out.println( "  depth_only: " + incidental[ 0 ] + " incidental event(s) ("
				+ String.join( ", ", incidentalTerms ) + ") paid nothing; the descent paid " + fmt( depthPaid )
				+ " and it is the whole of the run's positive score" );
	}


	public static void checkDepthOnlyKeepsTheFourNegatives(){
		EnvConfig config = new EnvConfig();
		config.rewardProfile = RewardProfile.DEPTH_ONLY;

		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardModel.TerminateReason.STALLED ){
			fail( "waiting under depth_only ended " + stall.endReason + " rather than stalling, so the"
					+ " stall price was not exercised." );
			return;
		}

		double stallPaid = stall.termTotal( RewardTerm.STALLED );
		if (Math.abs( stallPaid + config.stallPenalty ) > 1e-4 ){
			fail( "a stalled run under depth_only paid " + stallPaid + " for STALLED, expected "
					+ (-config.stallPenalty) + ". Stall is one of the four prices the profile keeps." );
			return;
		}

		double turnCost = stall.termTotal( RewardTerm.TURN_COST );
		double expectedTurnCost = -config.turnCost * (stall.turns - stall.notes( RewardTerm.NO_PROGRESS ) );
		if (Math.abs( turnCost - expectedTurnCost ) > 1e-5 ){
			fail( "a stalled run under depth_only charged " + turnCost + " in turn cost where "
					+ expectedTurnCost + " was expected: " + stall.turns + " turns, "
					+ stall.notes( RewardTerm.NO_PROGRESS ) + " of them free." );
			return;
		}

		EnvConfig limited = new EnvConfig();
		limited.rewardProfile = RewardProfile.DEPTH_ONLY;
		limited.turnLimitPerFloor = 12;
		limited.stallLimit = 100000;
		Outcome capped = playWaits( limited, 40 );
		if (capped.endReason != com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardModel.TerminateReason.TURN_LIMIT ){
			fail( "a run capped at " + limited.turnLimitPerFloor + " turns ended "
					+ capped.endReason + " rather than on the turn limit, so that price was not exercised." );
			return;
		}

		double cappedPaid = capped.termTotal( RewardTerm.TURN_LIMIT );
		if (Math.abs( cappedPaid + limited.turnLimitPenalty ) > 1e-4 ){
			fail( "a run that ran out of turns paid " + cappedPaid + " for TURN_LIMIT, expected "
					+ (-limited.turnLimitPenalty) + "." );
			return;
		}

		Outcome death = play( config, 60, "DEPTHONLYDEATH", turn -> {
			turn.env.step( Action.WAIT, 0 );
			if (Dungeon.hero != null && Dungeon.hero.isAlive()){
				Dungeon.hero.damage( Dungeon.hero.HP + 1, Dungeon.hero );
			}
		} );

		if (death.endReason != com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardModel.TerminateReason.DEATH ){
			fail( "the hero was killed under depth_only and the run ended " + death.endReason
					+ ", so the death price was not exercised." );
			return;
		}

		double deathPaid = death.termTotal( RewardTerm.DEATH );
		if (Math.abs( deathPaid + config.deathPenalty ) > 1e-4 ){
			fail( "a dead run under depth_only paid " + deathPaid + " for DEATH, expected "
					+ (-config.deathPenalty) + "." );
			return;
		}

		System.out.println( "  depth_only still charges stall " + fmt( stallPaid ) + ", turn limit "
				+ fmt( cappedPaid ) + ", death " + fmt( deathPaid ) + " and " + fmt( turnCost )
				+ " of turn cost" );
	}
}