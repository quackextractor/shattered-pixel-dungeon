## Headless Execution & Engine Stripping
To achieve extremely lightweight iterations reduced purely to numbers, bypass the `android`, `ios`, and `desktop` platform modules and operate strictly within the Java based `core` module. You must strip out the `com.watabou.noosa` UI framework and libGDX rendering logic, specifically bypassing classes like `GameScene.java`, `PixelScene.java`, and the entire `sprites` and `effects` packages. Running the core game loop headlessly allows you to simulate the environment without the overhead of rendering graphics.

## Vision & Input Injection
Restricting the AI to human level vision can be achieved by reading the game state strictly through the existing `FogOfWar.java` and `ShadowCaster.java` systems within the `tiles` and `mechanics` packages. This ensures the AI only receives data for visible tiles and entities, though it will still receive frame data for idle animations, allowing it to spot regular mimics. To execute moves, map the AI's intended actions directly into `SPDAction.java` or `HeroAction.java`, bypassing the UI based `InputHandler.java` and `ControllerHandler.java` entirely.

## Reward & Punishment Hooks
Instead of relying solely on the alternative method of rewarding depth and speed, you can implement your detailed state tracking by placing hooks directly into the game's core logic packages:
*   Progression: Track the primary goal of reaching deeper floors by monitoring `Dungeon.java` and `LevelTransition.java`.
*   Health & Status: Read the `Hero.java` class to reward gaining health, punish taking damage, and reward curing negative effects.
*   Inventory & Crafting: Monitor `Belongings.java` to track total money, inventory expanding items, and upgrades. You can reward alchemical crafting by tracking the creation of items like the meat pie, and reward item identification by monitoring `ScrollOfIdentify.java` and `Potion.java` usage.
*   Equipment Penalties: Intercept equip actions to apply punishments for wearing items with insufficient strength or equipping cursed items.
*   Hunger Mechanics: Monitor the `Hunger.java` buff to actively punish the AI for eating food before reaching the starving state.

## Seed Control & Trainer UI
To generalize across seeds while still enabling fair run comparisons, utilize `com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed` to lock the procedural generation. This allows you to run 1,000 simultaneous simulations on the exact same seed to extract the best performing run before rotating to a new seed. For the debugging and trainer interface, you can repurpose the `desktop` module to build a custom libGDX UI separate from the headless simulations. This interface can pull state data to generate the required red and green color coded floor summaries, overall run scores, and graphs depicting score over turns.

For a turn based, procedurally generated roguelike with a discrete grid and hidden information, Deep Reinforcement Learning is the ideal path. Because you are planning to run a massive number of headless simulations to generate experience rapidly, you need an architecture that scales well with parallel environments and can handle partial observability.

Here is the best approach to build the brain of this AI.

**The Core Algorithm: Proximal Policy Optimization (PPO)**
PPO is currently the industry standard for this type of environment. It is highly stable, relatively easy to tune, and excels when you can gather massive amounts of data from parallel workers.
*   Discrete Action Space: PPO works perfectly with the discrete actions of Pixel Dungeon (moving in eight directions, waiting, attacking, or using a specific inventory slot).
*   Parallel Scaling: Since you plan to run up to 1,000 simulations at once, PPO can effectively pool the gradients from all these simultaneous runs to make steady, reliable updates to the policy without catastrophic forgetting.
*   Alternative (IMPALA): If your simulation gets so fast that the neural network updates become the bottleneck, look into IMPALA (Importance Weighted Actor Learner Architecture). It is explicitly designed to decouple the headless game simulations from the learning network, maximizing throughput.

**The Network Architecture: CNN + LSTM**
The AI needs to process a grid and remember what it saw, requiring a two part neural network.
*   Spatial Vision (CNN): A Convolutional Neural Network is the best way to process the dungeon grid. Instead of feeding it raw pixels, you feed it a multi channel matrix of the exact data you pulled from `FogOfWar.java` and the level state. Channel 1 could be walls, Channel 2 enemies, Channel 3 items, and so on.
*   Memory (LSTM or GRU): Pixel Dungeon is a Partially Observable Markov Decision Process. Because of the Fog of War, the AI cannot see the whole map. A recurrent layer like an LSTM allows the AI to maintain a hidden state or "memory". This is strictly necessary so the AI can remember that it left a health potion three rooms behind, or remember the location of the stairs after exploring a dead end.

**The Training Strategy: Curriculum Learning**
Your detailed list of rewards and punishments is a perfect foundation for Curriculum Learning.
1.  Phase 1 (Dense Rewards): Start with your highly specific reward system. Rewarding the AI for gaining health, collecting gold, and crafting meat pies gives it immediate dopamine hits to learn basic survival and inventory management.
2.  Phase 2 (Sparse Rewards): As the AI gets competent, those specific micro rewards can actually cause bad habits (like hoarding items instead of progressing). You can slowly fade out the micro rewards and transition to your alternative plan: rewarding the AI purely for reaching deeper floors and punishing it for taking too many turns.

**Generalizing Across Seeds**
Your idea to lock the seed initially is exactly how you should start to ensure the network is actually learning to play rather than just getting lucky.
*   Train the AI on a single seed until it can consistently beat the first boss (Goo).
*   Once it masters one seed, expand the training pool to 10 locked seeds, then 100, and finally introduce fully random seeds. This forces the neural network to stop memorizing a specific map and start learning the general heuristics of the game.

There are three critical technical bridges from your initial plan that still need to be addressed to make this system work.

*   Recording and Executing Replays: Your plan requires plugging the best run back into the original game using the same seed so the history plays out identically. To achieve this, your headless training loop must log the exact sequence of `HeroAction` instances chosen by the AI. To watch the replay, you will launch the fully rendered `desktop` module, initialize the `DungeonSeed` with the saved seed, and intercept the `InputHandler` to feed it your logged action array sequentially instead of waiting for human input.
*   Action Timing and the Tick System: Shattered Pixel Dungeon is turn based, but it uses a time tick system where different actions cost different amounts of time. Moving takes a standard turn, but equipping heavy armor or using certain items might take longer. Your ML environment's step function cannot simply advance the game by one tick. It must yield control to the game engine's internal queue and only return the new state to the AI when the `Hero` is explicitly queried for its next action.
*   Encoding the Inventory: You want the game reduced purely to numbers. While the physical dungeon grid translates easily to a CNN, the player's `Belongings` require a fixed size vector. Because neural networks require consistent input sizes, you must create a flat, fixed length array representing the inventory slots. Each slot must encode the specific `Item`, its upgrade count, and whether it is a `CursedWand` or has other curses. This flattened inventory vector will bypass the CNN and be concatenated directly with the spatial data before entering the LSTM memory layer.

Here are the remaining architectural hurdles you must plan for before starting development.

**Targeting and Multi Step Actions**
Moving and attacking are single actions, but using ranged items requires selecting a target. When the AI decides to use a wand like `WandOfMagicMissile.java` or throw a potion like `PotionOfLiquidFlame.java`, it cannot just trigger the item. The action space must be structured to handle a secondary input for targeting coordinates. You must plan whether the AI outputs a target coordinate every turn (which is ignored unless aiming) or if the environment pauses to wait for a secondary targeting action.

**Context Switching and Menus**
The AI will not always be navigating the dungeon grid. Interacting with NPCs like `Shopkeeper.java` or `Blacksmith.java` changes the game state. The same applies to using `Alchemy.java` to craft the meat pie. You must implement a state machine that applies action masking. Action masking explicitly disables invalid actions (like trying to walk North) when the AI is stuck in a menu, forcing it to choose from valid menu selections like buying, selling, or closing the window.

**Exploit Mitigation and Turn Limits**
Your plan explicitly notes the need to prevent shop loops. Reinforcement learning agents are notorious for finding mechanical exploits instead of playing the game. If you reward gaining health, the AI might pace back and forth to abuse the `Regeneration.java` buff until it starves. To prevent infinite stalling, you must plan a hard cap on the number of turns allowed per floor, or apply a constant fractional negative reward for every turn taken to incentivize efficiency.

**Garbage Collection and Memory Pooling**
Running 1,000 simulations at once will put immense strain on the Java Virtual Machine. Even with the rendering engine stripped, background object creation will trigger the Garbage Collector, causing massive lag spikes across all threads. You must carefully audit the core game loop and implement object pooling for highly volatile classes. If visual logic like `FloatingText.java` or `Speck.java` cannot be perfectly isolated and removed, they must be pooled so the memory footprint remains stable during rapid iterations.