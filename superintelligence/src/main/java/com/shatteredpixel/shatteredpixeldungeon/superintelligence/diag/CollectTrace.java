package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class CollectTrace {

	static final String SEED = "COLLECT-CHECK";

	static final List< String > failures = new ArrayList<>();

	private CollectTrace(){}

	static class Step {
		final String action;
		final int slot;
		final String mode;
		final int heroPos;
		final float reward;

		Step( String action, int slot, String mode, int heroPos, float reward ){
			this.action = action;
			this.slot = slot;
			this.mode = mode;
			this.heroPos = heroPos;
			this.reward = reward;
		}

		@Override
		public boolean equals( Object other ){
			if (!(other instanceof Step )) return false;
			Step o = ( Step ) other;
			return action.equals( o.action ) && slot == o.slot && mode.equals( o.mode )
					&& heroPos == o.heroPos && reward == o.reward;
		}

		@Override
		public int hashCode(){
			return (action.hashCode() * 31 + slot) * 31 + mode.hashCode();
		}

		@Override
		public String toString(){
			return action + " " + slot + " " + mode + " @" + heroPos;
		}
	}

	static class Trace {
		final List< Step > steps = new ArrayList<>();
		String seedText = "";
	}

	static Trace record( SPDEnv env, EpisodeCollector collector ){
		Trace trace = new Trace();

		collector.listener( new EpisodeCollector.Listener() {
			private EnvMode mode;
			private String action;
			private int secondary;

			@Override public void onEpisodeStart( String seedText, String hero ){
				trace.seedText = seedText;
			}

			@Override public void onDecision( EnvMode chosenMode, Action chosenAction, int chosen ){
				mode = chosenMode;
				action = chosenAction.name();
				secondary = chosen;
			}

			@Override public void onStep( int heroPosition, float reward ){
				trace.steps.add( new Step( action, secondary, mode.name(),
						heroPosition, reward ));
			}
		} );

		collector.run( env, SEED, HeroClass.WARRIOR );
		collector.listener( null );

		return trace;
	}

	static Trace traceFrom( EnvConfig config, long netSeed, long policySeed, long sampleSeed ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( netSeed ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( policySeed ), new Random( sampleSeed ), 0.99f, 0.95f, 0.05f );
		return record( env, collector );
	}

	static void replay( SPDEnv env, EnvConfig config, Trace trace ){
		env.reset( trace.seedText.isEmpty() ? SEED : trace.seedText, HeroClass.WARRIOR );

		int i = 0;
		for (; i < trace.steps.size() && env.running(); i++){

			Step recorded = trace.steps.get( i );
			env.step( Action.valueOf( recorded.action ), recorded.slot );

			if (env.heroPosition() != recorded.heroPos){
				fail( "the recording diverges at step " + i + " of " + trace.steps.size()
						+ ": expected the hero at " + recorded.heroPos + ", it is at "
						+ env.heroPosition()
						+ ". A position recorded before the step rather than after looks exactly like "
						+ "this, and still plays back as a plausible run." );
				return;
			}
		}
		if (i < trace.steps.size()){
			fail( "the replay ended (" + env.endReason() + ") after " + i + " of " + trace.steps.size()
					+ " recorded steps, so the rest of the recording was never compared" );
		}
	}

	static void fail( String message ){
		failures.add( message );
	}

	static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}
}