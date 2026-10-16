package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.ObservationEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.Curriculum;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

/**
 * A single headless dungeon run, exposed with a reset/step interface.
 *
 * The shape follows the usual RL convention: {@link #reset} starts an episode, {@link #step}
 * consumes one action and returns the transition, and observation buffers plus masks are read from
 * the env itself and stay valid until the next step. Buffers are reused rather than reallocated,
 * which matters because a training sweep runs millions of steps and the garbage collector would
 * otherwise dominate the wall clock.
 *
 * One env owns one run, and because the game's state is almost entirely static - Dungeon.hero,
 * Actor.now, Level.visited all live in statics - a JVM can only host one run at a time. Parallel
 * rollouts are therefore separate worker JVMs. See {@code train.WorkerProcess}.
 */
public class SPDEnv {

	public static final int MAX_FLOOR_DEPTH = 26;

	private final EnvConfig config;
	private final HeadlessGame game;
	private final LevelPipeline pipeline;

	private final ActionMapper mapper;
	private final ObservationEncoder encoder;
	private final RewardLedger ledger;
	private final Curriculum curriculum;
	private final RewardModel reward;

	private final float[] actionMask = new float[ Action.size() ];
	private final float[] slotMask;
	private final float[] targetMask = new float[ ActionMapper.TARGET_COUNT ];

	private EnvMode mode = EnvMode.WORLD;
	private int turnsThisFloor;
	private int turnsTotal;
	private int lastDepth = 1;
	private int lastBranch = 0;
	private int stallCount;

	/** Steps on this floor that advanced no engine time, for the third stall guard. */
	private int freeStepsThisFloor;
	private int lastStallPos = -1;
	private float lastStallHp;

	/**
	 * Which guard ended the episode as STALLED, or {@link StallCause#NONE}.
	 *
	 * <p>Observation only. Four different guards raise the same {@code TerminateReason}, so a run that
	 * reports "99% STALLED" cannot say which of them did it, and the fix is different for each. Measured
	 * over three repeats of one config, 98-99% of all episodes end STALLED at {@code meanTurns} 122-140
	 * against {@code stallLimit} of 120 - see {@code issues.md} 27.
	 */
	private StallCause stallCause = StallCause.NONE;

	/** Mode the current step was decided in, and the engine clock at that moment. */
	private EnvMode stepEntered = EnvMode.WORLD;
	private float stepClock;

	private boolean running;
	private boolean terminated;
	private boolean truncated;
	private RewardModel.TerminateReason endReason = RewardModel.TerminateReason.OTHER;

	/** Seed the current episode is locked to, as text. Empty means a random seed. */
	private String seedText = "";

	private HeroClass heroClass = HeroClass.WARRIOR;

	public SPDEnv( EnvConfig config, HeadlessGame game ){
		this.config = config;
		this.game = game;
		this.pipeline = new LevelPipeline( game );
		this.mapper = new ActionMapper( config );
		this.encoder = new ObservationEncoder( config, mapper );
		this.ledger = new RewardLedger();
		this.ledger.profile( config.rewardProfile );
		this.curriculum = new Curriculum();
		this.reward = new RewardModel( config, ledger, curriculum );
		this.slotMask = new float[ config.maxSlots ];
	}

	/**
	 * The level pipeline this environment drives.
	 *
	 * <p>Exposed so a check can mirror the pipeline's own start-of-floor sequence exactly, rather than
	 * approximating it. Restarting without {@code attachSprites} leaves the hero without a sprite while
	 * the floor is built, which changes how much randomness generation consumes and so produced a
	 * different floor from the same seed.
	 */
	public LevelPipeline pipeline(){
		return pipeline;
	}

	/**
	 * Starts an episode.
	 *
	 * @param seed     seed text; empty or null means a fresh random seed
	 * @param heroClass which hero to play
	 * @return total reward accumulated by the previous episode, so a caller running a sweep can
	 *         attribute scores without inspecting the ledger
	 */
	public double reset( String seed, HeroClass heroClass ){
		double previous = ledger.total();

		ledger.reset();
		curriculum.reset();
		reward.resetSnapshot();

		this.seedText = (seed == null) ? "" : seed;
		this.heroClass = heroClass;

		RunState.clearRunStatics();

		pipeline.startRun( seedText, heroClass, 0 );

		if (seedText.isEmpty()){
			seedText = com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils
					.DungeonSeedCodes.encode( pipeline.currentSeed() );
		}

		mode = EnvMode.WORLD;
		turnsThisFloor = 0;
		turnsTotal = 0;
		lastDepth = 1;
		lastBranch = 0;
		stallCount = 0;
		freeStepsThisFloor = 0;
		lastStallPos = -1;
		lastStallHp = Dungeon.hero.HP;
		terminated = false;
		truncated = false;
		endReason = RewardModel.TerminateReason.OTHER;
		running = true;
		mapper.clearZeroTimeModeExit();

		encoder.resetExplored();
		mapper.refreshSlots();

		settle();

		ledger.beginFloor( Dungeon.depth, Dungeon.branch );
		reward.resetSnapshot();
		encoder.encode();
		refreshMasks();

		return previous;
	}

	/**
	 * Applies one agent decision.
	 *
	 * A step is not necessarily one game turn. Choosing USE or DROP costs one step and then the
	 * environment waits a second step for the slot, and an aiming item waits a third for the
	 * target. That is the deliberate cost of keeping the whole interface discrete.
	 *
	 * @param action the chosen action
	 * @param slot   slot index, option index, or target offset depending on {@link #mode()}
	 * @return the reward for the game turns this step consumed, which may be a sum over several
	 */
	public double step( Action action, int slot ){
		if (!running) throw new IllegalStateException( "step() after the episode ended" );

		GameScene.clearPendingCellListener();
		if (mode != EnvMode.TARGETING){

			SlotAction.clearPendingUseItem();
		}

		mapper.refreshSlots();

		boolean acted = false;

		stepEntered = mode;
		stepClock = Actor.now();

if (mode == EnvMode.WORLD){
			acted = mapper.apply( action, slot );
			if (action == Action.OPEN_INVENTORY){

				mode = EnvMode.INVENTORY;
				acted = true;
			} else if (action == Action.TALENT){

				mode = EnvMode.TALENT;
				acted = true;
			} else if (action == Action.USE || action == Action.DROP){

				mode = EnvMode.SLOT;
				if (WindowBridge.open()) mode = EnvMode.MENU;
			}
} else if (mode == EnvMode.MENU){
			acted = mapper.applySecondary( mode, action, slot );

			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.TALENT){

			acted = (action == Action.CANCEL) || mapper.applySecondary( mode, action, slot );
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.SLOT){
			boolean needsTarget = false;
			if (action == Action.USE){
				com.shatteredpixel.shatteredpixeldungeon.items.Item item = mapper.slot( slot );
				if (item != null){
					needsTarget = SlotAction.use( Dungeon.hero, item, config.allowEquipping );
				}
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = needsTarget ? EnvMode.TARGETING : EnvMode.WORLD;
			acted = true;
} else if (mode == EnvMode.TARGETING){

			if (action == Action.CANCEL){
				acted = SlotAction.cancelPendingUse( Dungeon.hero );
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.INVENTORY){

			boolean needsTarget = false;
			if (action == Action.USE){
				com.shatteredpixel.shatteredpixeldungeon.items.Item item = mapper.slot( slot );
				if (item != null){
					needsTarget = SlotAction.use( Dungeon.hero, item, config.allowEquipping );
				}
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = needsTarget ? EnvMode.TARGETING : EnvMode.WORLD;
			acted = true;
		}

		if (!acted){

			reward.noteEvent( RewardTerm.INVALID_ACTION );
		}

		double accumulated = settle();

		return accumulated;
	}

	/**
	 * Marks a zero-time exit out of a sub-mode, then publishes the masks.
	 *
	 * <p>Ordered, not two calls at the two places it matters: the decision has to be made before the mask
	 * is written, or the mask the agent reads for the next step is one step behind the step that earned
	 * it, and the refusal lands after the agent has already taken the free action it was meant to block.
	 */
	private void refreshMasksAfterDrain(){
		if (stepEntered != EnvMode.WORLD && mode == EnvMode.WORLD
				&& Actor.now() == stepClock){
			mapper.noteZeroTimeModeExit();
		}
		refreshMasks();
	}

	/**
	 * Runs the actor scheduler until the next agent decision point, scoring every game turn along
	 * the way.
	 *
	 * research.md: "It must yield control to the game engine's internal queue and only return the
	 * new state to the AI when the Hero is explicitly queried for its next action."
	 */
	private double settle(){
		double accumulated = 0;
		int guard = 0;

		while (running){
if (++guard > config.actorStepLimit){

				stallCause = StallCause.ACTOR_STEP_LIMIT;
				terminate( RewardModel.TerminateReason.STALLED );
				break;
			}

			if (WindowBridge.open()){

				WindowBridge.age();
				mode = EnvMode.MENU;
				break;
			}
			if (GameScene.pendingCellListener() != null){
				mode = EnvMode.TARGETING;
				break;
			}

			LevelPipeline.Outcome outcome = pipeline.runToHeroReady( config.actorStepLimit );

			if (outcome == LevelPipeline.Outcome.TRANSITION){
				accumulated += onFloorTransition();

				turnsThisFloor++;
				turnsTotal++;
				settleLanding();
				if (!running) break;
				accumulated += reward.step( encoder.encode(), true );
				checkFloorLimits();
				mapper.refreshSlots();
				refreshMasksAfterDrain();
				break;
			}
			if (outcome == LevelPipeline.Outcome.HERO_DEAD){
				accumulated += terminate( RewardModel.TerminateReason.DEATH );
				break;
			}
			if (outcome == LevelPipeline.Outcome.STALLED || outcome == LevelPipeline.Outcome.STEP_LIMIT){
				stallCause = StallCause.PIPELINE;
				accumulated += terminate( RewardModel.TerminateReason.STALLED );
				break;
			}

			turnsThisFloor++;
			turnsTotal++;

			int newlyExplored = encoder.encode();
			accumulated += reward.step( newlyExplored, true );

			checkFloorLimits();
			if (!running) break;

			mapper.refreshSlots();
			refreshMasksAfterDrain();

			if (WindowBridge.open()){
				WindowBridge.age();
				mode = EnvMode.MENU;
			}
			break;
		}

		return accumulated;
	}

	/**
	 * Gives the hero the one turn the game gives him on arrival at a new floor.
	 *
	 * <p>Separate from {@link #settle} because it must not score a turn. The hero arrives at time 0 on a
	 * floor where every other actor is also at time 0, so the scheduler picks him first and
	 * {@code Hero.act()} - with no action pending - calls {@code ready()} and returns without spending
	 * anything. The loop then finds him waiting for input and stops on its first iteration, which is
	 * what makes "one act" and "a drain" different things here rather than the same thing at different
	 * sizes.
	 *
	 * <p>What the act carries is the point. {@code Hero.act()} runs {@code checkVisibleMobs()}, and
	 * that is where the new floor's sleeping mobs notice a hero standing among them; a mob seeing the
	 * hero for the first time calls {@code Hero.interrupt()}, which discards the pending action. Handing
	 * control to the agent before this happens means the agent's first action on every new floor is
	 * spent being interrupted by a rat waking up, and the recording says so while the real game does not.
	 *
	 * <p>Terminates on the same outcomes the rest of {@code settle} does, so a hero that dies in the
	 * landing act - a chasm fall is the case that exists - ends the episode rather than being handed
	 * back with no hero.
	 */
	private void settleLanding(){
		LevelPipeline.Outcome landed = pipeline.runToHeroReady( config.actorStepLimit );

		if (landed == LevelPipeline.Outcome.HERO_DEAD){
			terminate( RewardModel.TerminateReason.DEATH );
		} else if (landed == LevelPipeline.Outcome.STALLED
				|| landed == LevelPipeline.Outcome.STEP_LIMIT){
			stallCause = StallCause.PIPELINE;
			terminate( RewardModel.TerminateReason.STALLED );
		}
	}

	private double onFloorTransition(){
		LevelPipeline.Transition transition = pipeline.handleTransition();

		if (transition == LevelPipeline.Transition.FALL){
			pipeline.handleFall();
		}

		int depth = Dungeon.depth;
		int branch = Dungeon.branch;

		curriculum.observe( depth );
		ledger.shapingScale( curriculum.shapingScale() );

		if (depth > lastDepth && Dungeon.bossLevel()){
			reward.note( RewardTerm.BOSS_SLAIN, config.depthReward );
		}

		if (depth > MAX_FLOOR_DEPTH){

			settleHeroOnLanding();
			return terminate( RewardModel.TerminateReason.VICTORY );
		}

		if (depth != lastDepth || branch != lastBranch){
			if (depth > lastDepth){

				ledger.markFloorCleared();
			}
			lastDepth = depth;
			lastBranch = branch;
			turnsThisFloor = 0;
			encoder.resetExplored();
			mapper.refreshSlots();

			stallCount = 0;
			freeStepsThisFloor = 0;
			lastStallPos = -1;
			ledger.beginFloor( depth, branch );
		}

		refreshMasksAfterDrain();
		return ledger.flushTurn();
	}

	private void settleHeroOnLanding(){

		mapper.refreshSlots();
		encoder.encode();
	}

	private void checkFloorLimits(){

		if (turnsThisFloor >= config.turnLimitPerFloor){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}
		if (turnsTotal >= config.turnLimitTotal){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}

		Hero hero = Dungeon.hero;
		if (hero.pos == lastStallPos && hero.HP == lastStallHp){
			stallCount++;
			if (stallCount >= config.stallLimit){
				stallCause = StallCause.POSITION;
				terminate( RewardModel.TerminateReason.STALLED );
				return;
			}
		} else {
			stallCount = 0;
			lastStallPos = hero.pos;
			lastStallHp = hero.HP;
		}

if (!reward.lastStepAdvancedClock()){
			freeStepsThisFloor++;
			if (freeStepsThisFloor >= config.freeStepBudget){
				stallCause = StallCause.FREE_STEP;
				terminate( RewardModel.TerminateReason.STALLED );
			}
		}
	}

	/**
	 * Ends the episode, once.
	 *
	 * <p>Idempotent, and that is the fix rather than a tidy-up. Two of this method's callers could
	 * reach it twice for the same episode: {@code settle}'s {@code actorStepLimit} guard did exactly
	 * that, charging {@code STALLED}'s terminal reward -5.0 twice over for -10.0. Removing the duplicate
	 * call fixed that instance; making the method refuse a second termination fixes the class, because
	 * every future caller gets the same protection for free.
	 *
	 * <p>The guard is also what makes the reason authoritative. Without it, a second call could
	 * overwrite {@code endReason} with a less specific one, so an episode that died would report
	 * whatever the last caller said — and the end-reason breakdown added for this would lie.
	 */
	private double terminate( RewardModel.TerminateReason reason ){
		if (terminated) return 0;

		endReason = reason;
		terminated = true;
		truncated = SPDEnv.isTruncation( reason );
		running = false;
		return reward.terminate( reason );
	}

	/**
	 * Whether a reason cut the episode short rather than deciding it.
	 *
	 * <p>A truncation is bootstrapped by GAE — the episode stopped early and the value carries on past
	 * it. The complement of {@link #isNaturalEnding}, and the pair is exhaustive over the reasons that
	 * can end an episode, which is why both are named rather than inlined at their one call site.
	 */
	public static boolean isTruncation( RewardModel.TerminateReason reason ){
		return !isNaturalEnding( reason );
	}

	public EnvMode mode(){ return mode; }
	public boolean running(){ return running; }
	public boolean terminated(){ return terminated; }
	public boolean truncated(){ return truncated; }
	public RewardModel.TerminateReason endReason(){ return endReason; }

	/** True when the episode ended by dying or winning, rather than running out of budget. */
	public boolean endedNaturally(){
		return isNaturalEnding( endReason );
	}

	/**
	 * Whether a reason counts as the game ending the episode, rather than the harness cutting it short.
	 *
	 * <p>Static and taking the reason rather than reading a field, so a check can assert the
	 * classification for every reason without having to produce an episode that ends each way. Floor 1
	 * is survivable by wandering and beating the boss is not something a test can play out, so the only
	 * way to cover the whole set is to ask the predicate directly — and asking it is what keeps this
	 * from becoming a restatement of itself, which is the failure mode a table of expected values here
	 * would have.
	 *
	 * <p>DEATH and VICTORY are the game's own outcomes. STALLED and TURN_LIMIT are guards that fired
	 * because the harness stopped feeding the episode, and both are marked truncated so GAE bootstraps
	 * through them: the episode was cut short, and the value carries on past it.
	 */
	public static boolean isNaturalEnding( RewardModel.TerminateReason reason ){
		return reason == RewardModel.TerminateReason.DEATH
				|| reason == RewardModel.TerminateReason.VICTORY;
	}

public float[] actionMask(){ return actionMask; }

	/** Which guard ended the episode as STALLED, or {@link StallCause#NONE}. See the field. */
	public StallCause stallCause(){ return stallCause; }

	/** Whether the last step advanced engine time. A step that did not counts against the free budget. */
	public boolean lastStepAdvancedClock(){ return reward.lastStepAdvancedClock(); }
	public float[] slotMask(){ return slotMask; }
	public float[] targetMask(){ return targetMask; }

	private final float[] actionLogits = new float[ Action.size() ];
	private final float[] slotLogits = new float[ 128 ];
	private final float[] targetLogits = new float[ ActionMapper.TARGET_COUNT ];

	/** Logits the policy is considering on the action head. Only valid before a step. */
	public float[] actionLogitsPreview(){ return actionLogits; }

	/** Logits the policy is considering on the slot head. Only valid before a step. */
	public float[] slotLogitsPreview(){ return slotLogits; }

	/** Logits the policy is considering on the target head. Only valid before a step. */
	public float[] targetLogitsPreview(){ return targetLogits; }

	/** Lets the policy publish its current head outputs for the coming step. */
	public void setPolicyOutputs( float[] actions, float[] slots, float[] targets ){
		System.arraycopy( actions, 0, actionLogits, 0,
				Math.min( actions.length, actionLogits.length ) );
		System.arraycopy( slots, 0, slotLogits, 0, Math.min( slots.length, slotLogits.length ) );
		System.arraycopy( targets, 0, targetLogits, 0, Math.min( targets.length, targetLogits.length ) );
	}

	public float[] grid(){ return encoder.grid(); }
	public float[] inventory(){ return encoder.inventory(); }
	public float[] heroFeatures(){ return encoder.hero(); }

	public int gridWidth(){ return encoder.gridWidth(); }
	public int gridHeight(){ return encoder.gridHeight(); }
	public int spatialChannels(){ return encoder.spatialChannels(); }

	public RewardLedger ledger(){ return ledger; }
	public Curriculum curriculum(){ return curriculum; }
	public ActionMapper mapper(){ return mapper; }
	public EnvConfig config(){ return config; }

	/**
	 * The seed this run actually used.
	 *
	 * The resolved seed, not the requested one: an empty request means the game drew one, and this is
	 * that draw's code. Naming it is what makes a random-seed episode replayable and verifiable.
	 */
	public String seedText(){ return seedText; }
	public int turnsTotal(){ return turnsTotal; }
	public int depth(){ return Dungeon.depth; }

	/**
	 * The deepest floor this run has been on.
	 *
	 * <p>Not the same as {@link #depth()}, and the difference is not cosmetic. {@code depth()} is where
	 * the hero is standing now, so a run that descends and then walks back up to die on floor 1 reports
	 * 1 - which is what {@code duelist-mid} did, on a recording that reaches floor 2 and is the only
	 * corpus file that does. Every report, chart and milestone in this project means "depth" as
	 * <i>progress</i>, and {@code TODO.md} has spent several entries reasoning about a corpus where
	 * "every recording is depth=1"; a header that can say 1 for a run that went down would keep saying it.
	 *
	 * <p>{@code Statistics.deepestFloor} is the game's own figure for this, updated by
	 * {@code Dungeon.newLevel} and reset by {@code Dungeon.init}, so it is per-run and it is not a second
	 * opinion this module had to form.
	 */
	public int deepestDepth(){ return com.shatteredpixel.shatteredpixeldungeon.Statistics.deepestFloor; }

	/** Hero cell, for replay divergence checks. */
	public int heroPosition(){ return Dungeon.hero.pos; }

	private void refreshMasks(){
		mapper.actionMask( mode, actionMask );
		mapper.slotMask( mode, slotMask );
		for (int i = 0; i < targetMask.length; i++){
			targetMask[ i ] = mapper.targetMask( i ) ? 1f : 0f;
		}
	}

	/** Ends the current episode without scoring, used when a worker is shutting down. */
	public void abandon(){
		running = false;
		terminated = true;
	}

/** Description of what the environment is waiting for, for diagnostics. */
	public String describeMode(){
		switch (mode) {
			case MENU:      return "menu(" + WindowBridge.describe() + ")";
			case TARGETING: return "targeting";
			case SLOT:      return "slot";
			case INVENTORY: return "inventory";
			case TALENT:    return "talent(" + talentChoices().size() + " choices)";
			default:        return "world";
		}
	}

	/** True when the open dialog's option can be chosen, for scripted policies. */
	public boolean windowOptionSelectable( int index ){
		return WindowBridge.optionSelectable( index );
	}

	/** Number of options the open dialog offers. */
	public int windowOptionCount(){
		return WindowBridge.optionCount();
	}

	/** The talents the hero could spend a point on, in the order the slot head indexes them. */
	public java.util.List<com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent> talentChoices(){
		return mapper.talentChoices();
	}
}
