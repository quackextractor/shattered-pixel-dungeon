package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ReplayCatalogCheck {

	private static final int CHECKS = 22;

	public static void main( String[] args ){
		try {
			ReplayCatalogFixture.workDir = Files.createTempDirectory( "spd-replaycheck" ).toFile();
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIndex.useIndexFile(
					new File( ReplayCatalogFixture.workDir, "replay-dirs.txt" ));
		} catch (IOException e){
			System.err.println( "[ERROR] could not create a scratch directory: " + e.getMessage() );
			System.exit( 2 );
			return;
		}

		try {
			ReplayCatalogListingCheck.checkRankedByScoreWithinAGroup();
			ReplayCatalogListingCheck.checkGroupedByHeroClass();
			ReplayCatalogListingCheck.checkDepthBreaksAScoreTie();
			ReplayCatalogListingCheck.checkDamagedFilesAreListedNotHidden();
			ReplayCatalogListingCheck.checkDamagedFilesSortLast();
			ReplayCatalogListingCheck.checkAMalformedFileIsListedNotFatal();
			ReplayCatalogListingCheck.checkLookupBySeedWithoutExtension();
			ReplayCatalogListingCheck.checkLookupByFilenameWithExtension();
			ReplayCatalogListingCheck.checkLookupIsCaseInsensitiveAndAcceptsAPath();
			ReplayCatalogListingCheck.checkSectionsSplitByProvenance();
			ReplayCatalogListingCheck.checkDepthIsAReadableColumn();
			ReplayCatalogListingCheck.checkATrainerDirectoryAloneIsNotTheCorpus();

			ReplayCatalogGroupingCheck.checkHeaderOnlyReadMatchesAFullRead();
			ReplayCatalogGroupingCheck.checkHeaderOnlyReadAgreesOnWatchability();
			ReplayCatalogGroupingCheck.checkDeduplicatesTheSameSeed();
			ReplayCatalogGroupingCheck.checkAmbiguousSeedIsRefusedNotGuessed();
			ReplayCatalogGroupingCheck.checkIndexedDirectoriesAreDiscovered();
			ReplayCatalogGroupingCheck.checkTheBlurbAgreesWithItsOwnRows();
			ReplayCatalogGroupingCheck.checkTheSameDirectoryUnderTwoSpellingsIsOneDirectory();
			ReplayCatalogGroupingCheck.checkASharedSeedResolvesByFileName();
			ReplayCatalogGroupingCheck.checkAGroupIsItsOwnSection();
			ReplayCatalogGroupingCheck.checkSubdirectoriesAreScannedAndRejectedIsNot();
		} finally {
			ReplayCatalogFixture.deleteTree( ReplayCatalogFixture.workDir );
		}

		if (ReplayCatalogFixture.failures.isEmpty()){
			System.out.println( "[OK]     replay catalog: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  replay catalog: " + ReplayCatalogFixture.failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : ReplayCatalogFixture.failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
}