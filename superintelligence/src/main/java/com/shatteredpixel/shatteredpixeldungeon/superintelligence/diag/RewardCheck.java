package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

public class RewardCheck {

	private static final int CHECKS = 41;

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		RewardTerminalCheck.checkStallingIsNotCheaperThanDying( config );
		RewardTerminalCheck.checkNoOpActionsAreNotRefusals( config );
		RewardTerminalCheck.checkAnItemActionInWorldIsNotARefusal( config );
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
		RewardDescentCheck.checkTheBossIsPaidForOnLeavingItsFloor( config );
		RewardDescentCheck.checkPickingUpTheAmuletWins( config );
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
		RewardStairCheck.checkClosingTheGapToTheStairsPays( config );
		RewardStairCheck.checkStairShapingCannotBeFarmed( config );
RewardStairCheck.checkStairShapingIsOffAtZero( config );
		StairPotentialCheck.checkTheFieldIsBuiltOncePerTerrain();
RewardShapingCheck.checkPickingUpAnItemPays();
    	RewardShapingCheck.checkPickingUpGoldPays();
    	RewardShapingCheck.checkPickingUpUnderfootPays();
		RewardShapingCheck.checkAMenuNoOpEmitsAndPays();

		RewardSurvivalCheck.checkEatingBeforeHungerIsPaidFor( config );
		RewardSurvivalCheck.checkALevelUpPaysItsHealthOnce( config );
		RewardSurvivalCheck.checkOnlyIdentifyingSomethingCarriedPays( config );
		RewardSurvivalCheck.checkTheFirstStepIsScored( config );

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