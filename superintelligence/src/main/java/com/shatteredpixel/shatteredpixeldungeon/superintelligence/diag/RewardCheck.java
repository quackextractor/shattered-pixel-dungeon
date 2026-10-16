package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

public class RewardCheck {

	private static final int CHECKS = 33;

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		RewardTerminalCheck.checkStallingIsNotCheaperThanDying( config );
		RewardTerminalCheck.checkNoOpActionsAreNotRefusals( config );
		RewardTerminalCheck.checkAnItemActionInWorldIsARefusal( config );
		RewardTerminalCheck.checkStallIsNotANaturalEnding( config );
		RewardTerminalCheck.checkStallCostsMoreThanDying( config );
		RewardTerminalCheck.checkTerminalsAreOrdered( config );
		RewardTerminalCheck.checkDepthBeatsAPerFloorTurnLimit( config );
		RewardTerminalCheck.checkExplorationCannotOutbuyADescent( config );
		RewardTerminalCheck.checkStallPathRecordsOnce( config );
		RewardTerminalCheck.checkTheHeroCanDie( config );
		RewardTerminalCheck.checkWaitingIsNotAFreeStep( config );
		RewardTerminalCheck.checkRestIsNotAOneWayDoor( config );
		RewardTerminalCheck.checkFreeStepLoopIsBounded( config );

		RewardDescentCheck.checkOnlyANewFloorIsPaidFor( config );
		RewardDescentCheck.checkDefaultProfilePaysEveryTerm();
		RewardDescentCheck.checkDepthOnlyPaysOnlyForDepth();
		RewardDescentCheck.checkDepthOnlyKeepsTheFourNegatives();

		RewardCombatCheck.checkKillingIsWorthDoing( config );
		RewardCombatCheck.checkPickingUpAndDroppingIsNotProfitable( config );
		RewardCombatCheck.checkSpendingATalentIsWorthALot( config );
		RewardCombatCheck.checkRemovingACursePays( config );
		RewardCombatCheck.checkWearingSomethingTooStrongIsPaidFor( config );
		RewardCombatCheck.checkAMenuNoOpIsUnreachable( config );

		RewardShapingCheck.checkFreeStepsCostSomething( config );
		RewardShapingCheck.checkAMultiTurnActionPaysForEveryTurn( config );
		RewardShapingCheck.checkSearchingIsNotCheaperThanActing( config );
		RewardShapingCheck.checkClosingTheGapToTheStairsPays( config );
		RewardShapingCheck.checkStairShapingCannotBeFarmed( config );
RewardShapingCheck.checkStairShapingIsOffAtZero( config );
	RewardShapingCheck.checkPickingUpAnItemPays();
	RewardShapingCheck.checkPickingUpGoldPays();

	RewardTypeCheck.checkEveryTermHasATypeAndTheSubtotalsSum();
	RewardTypeCheck.checkTheReportRendersColourCodedTypeTotals();

	if (RewardFixtures.failures.isEmpty()){
			System.out.println( "[OK]     reward signals: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  reward signals: " + RewardFixtures.failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : RewardFixtures.failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
}