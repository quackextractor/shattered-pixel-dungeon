package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The headless half of the game's talent pane: which talents a point can go on, and spending it.
 *
 * In the game this lives in {@code TalentButton} -> {@code TalentsPane} -> {@code WndHero}, all of which
 * need a renderer, so the agent could level up and then never act on the point it had been given.
 * {@code RewardTerm.TALENT_UPGRADE} is wired, gated and unearnable, and the first point arrives at
 * level 2 - two rats on floor 1 - so this is not an endgame gap.
 *
 * <p>The order is the game's own: tiers in ascending order, and inside a tier the insertion order of
 * {@link Hero#talents}, which is a {@code LinkedHashMap} built by {@code Talent.initClassTalents} and
 * therefore stable across processes. A recorded choice therefore names the same talent in the trainer
 * and in the viewer.
 */
public final class TalentPicker {

	private TalentPicker() {}

	/**
	 * Every talent the hero could spend a point on right now, in the game's order.
	 *
	 * A talent qualifies when its tier has a point available and it is not already at
	 * {@link Talent#maxPoints()}. A tier with no point - a hero below the level that opens it, or one
	 * with no subclass or armour ability behind tiers 3 and 4 - is skipped entirely, which is the same
	 * test the game applies.
	 */
	public static List<Talent> available( Hero hero ){
		List<Talent> out = new ArrayList<>();

		if (hero == null || !hero.isAlive()) return out;

		int tiers = Math.min( hero.talents.size(), Talent.MAX_TALENT_TIERS );

		for (int i = 0; i < tiers; i++){
			int tier = i + 1;
			if (availablePoints( hero, tier ) <= 0) continue;

			LinkedHashMap<Talent, Integer> tierTalents = hero.talents.get( i );
			if (tierTalents == null) continue;

			for (Talent talent : tierTalents.keySet()){
				Integer points = tierTalents.get( talent );
				if (points == null || points >= talent.maxPoints()) continue;
				out.add( talent );
			}
		}

		return out;
	}

	/**
	 * Spends one point on {@code index} of {@link #available}.
	 *
	 * @return whether a point was spent, which is also whether the agent's choice was legal
	 */
	public static boolean spend( Hero hero, int index ){
		List<Talent> options = available( hero );
		if (index < 0 || index >= options.size()) return false;

		hero.upgradeTalent( options.get( index ) );
		return true;
	}

	/** How many talent points the hero could spend without any further change. */
	public static int spendableCount( Hero hero ){
		if (hero == null) return 0;

		int total = 0;
		int tiers = Math.min( hero.talents.size(), Talent.MAX_TALENT_TIERS );
		for (int tier = 1; tier <= tiers; tier++){
			total += Math.max( 0, availablePoints( hero, tier ) );
		}
		return total;
	}

	private static int availablePoints( Hero hero, int tier ){
		try {
			return hero.talentPointsAvailable( tier );
		} catch (IndexOutOfBoundsException e){
			return 0;
		}
	}
}