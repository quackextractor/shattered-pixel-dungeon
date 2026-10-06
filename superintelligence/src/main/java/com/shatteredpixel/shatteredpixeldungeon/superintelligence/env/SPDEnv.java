package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
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

	// --- episode state ---

	private EnvMode mode = EnvMode.WORLD;
	private int turnsThisFloor;
	private int turnsTotal;
	private int lastDepth = 1;
	private int lastBranch = 0;
	private int stallCount;
	private int lastStallPos = -1;
	private float lastStallHp;

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
		this.curriculum = new Curriculum();
		this.reward = new RewardModel( config, ledger, curriculum );
		this.slotMask = new float[ config.maxSlots ];
	}

	// --------------------------------------------------------------------------- lifecycle

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

		pipeline.startRun( seedText, heroClass, 0 );

		mode = EnvMode.WORLD;
		//A dialog belongs to one run. Clearing it per step instead meant the step that could have
		//answered it arrived to find it already gone, so MENU could not be reached at all. It cannot
		//survive into a WORLD step anyway: settle() switches to MENU whenever a dialog is open.
		GameScene.clearHeadlessWindow();
		SlotAction.clearPendingUseItem();
		turnsThisFloor = 0;
		turnsTotal = 0;
		lastDepth = 1;
		lastBranch = 0;
		stallCount = 0;
		lastStallPos = -1;
		lastStallHp = Dungeon.hero.HP;
		terminated = false;
		truncated = false;
		endReason = RewardModel.TerminateReason.OTHER;
		running = true;

		encoder.resetExplored();
		mapper.refreshSlots();

		//let the engine settle so the hero is genuinely ready before the first observation
		settle();

		ledger.beginFloor( Dungeon.depth, Dungeon.branch );
		reward.resetSnapshot();
		encoder.encode();
		refreshMasks();

		return previous;
	}

	// --------------------------------------------------------------------------- stepping

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

		//A dialog is state the agent is meant to be answering, so it survives into the step that
		//resolves it. It used to be cleared on every step, which meant the step that could have
		//answered it arrived to find it already gone: entering MENU needs a dialog, so WindowBridge
		//had nothing to select and every rollout that reached one sat choosing at nothing until it
		//stalled. MENU was not merely hard to record - it was unreachable in a real run.
		//
		//The aim listener is deliberately still cleared every step, including TARGETING.
		//SlotAction.pendingUseItem survives, and resolveTarget falls back to castAt, which throws
		//without touching the sprite pool. Keeping the listener instead routes the throw through the
		//game's own path, which recycles a missile sprite from hero.sprite.parent - and headless
		//there is no parent to recycle from, so the throw died on a NullPointerException instead.
		GameScene.clearPendingCellListener();
		if (mode != EnvMode.TARGETING){
			//Only drop a stale aim request when a fresh turn begins. Clearing it wiped the item being
			//aimed with before the agent could resolve the aim, so the following TARGETING step had
			//nothing to act on: the throw never completed, the hero stayed mid-action and the
			//pipeline gave up, ending every episode that reached an aim as STALLED.
			SlotAction.clearPendingUseItem();
		}

		mapper.refreshSlots();

		boolean acted = false;

if (mode == EnvMode.WORLD){
			acted = mapper.apply( action, slot );
			if (action == Action.OPEN_INVENTORY){
				//the one and only entry into INVENTORY. It was documented on the action but never
				//assigned here, and OPEN_INVENTORY fell through to the generic cell handling as a
				//no-op, so the mode was unreachable: no rollout could contain it and no replay could
				//ever exercise it.
				mode = EnvMode.INVENTORY;
				acted = true;
			} else if (action == Action.USE || action == Action.DROP){
				//the slot and, if needed, the aim are chosen on the following steps
				mode = EnvMode.SLOT;
				if (WindowBridge.open()) mode = EnvMode.MENU;
			}
} else if (mode == EnvMode.MENU){
			acted = mapper.applySecondary( mode, action, slot );
			//Back to WORLD, the way TARGETING does. Without this the mode never changed hands again:
			//the dialog could be answered successfully and the episode still sat in MENU forever,
			//because nothing ever put it back, so the next action was interpreted as another option
			//choice. If the dialog is genuinely still open, settle() puts it straight back to MENU.
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
			//CANCEL has to be handled apart from an aim, otherwise it is read as a throw at the
			//default target - which spent the stone - and the hero was never released.
			if (action == Action.CANCEL){
				acted = SlotAction.cancelPendingUse( Dungeon.hero );
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.INVENTORY){
			//mirrors the SLOT branch rather than going through applySecondary, because only use()
			//reports whether the item needs an aim and that answer decides the next mode
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
			reward.note( RewardTerm.INVALID_ACTION, 0 );
		}

		return settle();
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
				terminate( RewardModel.TerminateReason.STALLED );
				break;
			}

			//a dialog opened during the step takes priority over the next turn
			if (WindowBridge.open()){
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
				continue;
			}
			if (outcome == LevelPipeline.Outcome.HERO_DEAD){
				accumulated += terminate( RewardModel.TerminateReason.DEATH );
				break;
			}
			if (outcome == LevelPipeline.Outcome.STALLED || outcome == LevelPipeline.Outcome.STEP_LIMIT){
				accumulated += terminate( RewardModel.TerminateReason.STALLED );
				break;
			}

			//READY: score the turn that just completed
			turnsThisFloor++;
			turnsTotal++;

			int newlyExplored = encoder.encode();
			accumulated += reward.step( newlyExplored, true );

			checkFloorLimits();
			if (!running) break;

			//the agent just waited, or the engine advanced only other actors; observe and hand back
			mapper.refreshSlots();
			refreshMasks();
			break;
		}

		return accumulated;
	}

	// --------------------------------------------------------------------------- transitions

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
			//cleared the dungeon; the last transition is what ends a winning run
			settleHeroOnLanding();
			return terminate( RewardModel.TerminateReason.VICTORY );
		}

		if (depth != lastDepth || branch != lastBranch){
			lastDepth = depth;
			lastBranch = branch;
			turnsThisFloor = 0;
			encoder.resetExplored();
			mapper.refreshSlots();
			ledger.beginFloor( depth, branch );
		}

		reward.resetSnapshot();
		encoder.encode();
		refreshMasks();
		return ledger.flushTurn();
	}

	private void settleHeroOnLanding(){
		//one extra pass so the hero is on the new floor and the landing cost is scored
		mapper.refreshSlots();
		encoder.encode();
	}

	// --------------------------------------------------------------------------- termination

	private void checkFloorLimits(){
		//research.md: a hard cap per floor, or a constant fractional penalty per turn, to stop
		//an agent farming Regeneration or pacing in a shop forever
		if (turnsThisFloor >= config.turnLimitPerFloor){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}
		if (turnsTotal >= config.turnLimitTotal){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}

		//a second, softer guard: nothing at all has changed in a long while
		Hero hero = Dungeon.hero;
		if (hero.pos == lastStallPos && hero.HP == lastStallHp){
			stallCount++;
			if (stallCount >= config.stallLimit){
				terminate( RewardModel.TerminateReason.STALLED );
			}
		} else {
			stallCount = 0;
			lastStallPos = hero.pos;
			lastStallHp = hero.HP;
		}
	}

	private double terminate( RewardModel.TerminateReason reason ){
		endReason = reason;
		terminated = true;
		truncated = (reason == RewardModel.TerminateReason.TURN_LIMIT
				|| reason == RewardModel.TerminateReason.STALLED);
		running = false;
		return reward.terminate( reason );
	}

	// --------------------------------------------------------------------------- accessors

	public EnvMode mode(){ return mode; }
	public boolean running(){ return running; }
	public boolean terminated(){ return terminated; }
	public boolean truncated(){ return truncated; }
	public RewardModel.TerminateReason endReason(){ return endReason; }

	/** True when the episode ended by dying or winning, rather than running out of budget. */
	public boolean endedNaturally(){
		return endReason == RewardModel.TerminateReason.DEATH
				|| endReason == RewardModel.TerminateReason.VICTORY;
	}

public float[] actionMask(){ return actionMask; }
	public float[] slotMask(){ return slotMask; }
	public float[] targetMask(){ return targetMask; }

	// --- scratch buffers, filled by the policy immediately before each step ---

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
	public String seedText(){ return seedText; }
	public int turnsTotal(){ return turnsTotal; }
	public int depth(){ return Dungeon.depth; }

	/** Hero cell, for replay divergence checks. */
	public int heroPosition(){ return Dungeon.hero.pos; }

	private void refreshMasks(){
		mapper.actionMask( mode, actionMask );
		mapper.slotMask( slotMask );
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
}