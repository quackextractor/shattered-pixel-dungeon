package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

public class RewardTypeCheck {

	private static boolean allTermsTyped(){
		for (RewardTerm term : RewardTerm.values()){
			if (term.type() != null) continue;
			RewardFixtures.fail( term.name() + " has no reward type, so a dashboard grouped by type"
					+ " would silently drop it and the subtotals could not reconstruct the score" );
			return false;
		}
		return true;
	}

	static void checkEveryTermHasATypeAndTheSubtotalsSum(){
		EnvConfig config = new EnvConfig();

		if (!allTermsTyped()) return;

		RewardFixtures.Outcome outcome = RewardFixtures.play( config, 40, "REWARDTYPECHECK", turn -> {
			if (turn.step == 2) RewardFixtures.descend( turn.env );
			turn.env.step( Action.WAIT, 0 );
		});

		RewardLedger ledger = outcome.ledger;
		if (ledger == null){
			RewardFixtures.fail( "no ledger came back, so the type subtotals are unchecked" );
			return;
		}

		double sum = 0;
		for (double total : ledger.typeTotals().values()) sum += total;

		if (Math.abs( sum - ledger.total() ) > 1e-6){
			RewardFixtures.fail( "the type subtotals sum to " + RewardFixtures.fmt( sum ) + " and the"
					+ " total the run scored is " + RewardFixtures.fmt( ledger.total() ) + ", so grouping"
					+ " by type would report a score the run did not earn" );
			return;
		}

		int counted = 0;
		for (int events : ledger.typeCounts().values()) counted += events;

		int notes = 0;
		for (RewardTerm term : RewardTerm.values()) notes += ledger.notes( term );

		if (counted != notes){
			RewardFixtures.fail( "the type event counts sum to " + counted + " and the ledger recorded "
					+ notes + " events, so the type table would disagree with the term table" );
			return;
		}

		System.out.println( "  every reward term has a type, and the subtotals reconstruct the run: "
				+ RewardFixtures.fmt( sum ) + " over " + counted + " events" );
	}


	static void checkTheReportRendersColourCodedTypeTotals(){
		EnvConfig config = new EnvConfig();

		if (!allTermsTyped()) return;

		RewardFixtures.Outcome outcome = RewardFixtures.play( config, 40, "REPORTTYPE", turn -> {
			if (turn.step == 2) RewardFixtures.descend( turn.env );
			turn.env.step( Action.WAIT, 0 );
		});

		boolean wasEnabled = Ansi.enabled();
		Ansi.enable( true );
		String rendered;
		try {
			rendered = new RunReport( outcome.ledger, "REPORTTYPE" ).render( 40 );
		} finally {
			Ansi.enable( wasEnabled );
		}

		int header = rendered.indexOf( "type totals" );
		if (header < 0){
			RewardFixtures.fail( "RunReport prints no 'type totals' block, so a run shows floor totals and"
					+ " thirty individual terms with nothing grouping them" );
			return;
		}

		String block = rendered.substring( header );
		int nextSection = block.indexOf( "\n\n" );
		if (nextSection > 0) block = block.substring( 0, nextSection );

		if (!block.contains( Ansi.GREEN )){
			RewardFixtures.fail( "no positive type subtotal is colour-coded green, so the reader reads a"
					+ " positive number in the same colour as a negative one" );
			return;
		}

		if (!block.contains( Ansi.RED )){
			RewardFixtures.fail( "no negative type subtotal is colour-coded red, which is the half of the"
					+ " requirement a run of pure costs would reach first" );
			return;
		}

		System.out.println( "  the report renders a colour-coded type totals block, green above zero and"
				+ " red below it" );
	}
}