# Scattered Pixel Dungeon Superintelligence

# Initial Approach & Simplification

To start, I would first inform myself on what kind of learning method is best. Then, I would simplify the game so I can run more simulations at once. Luckily, the game should be extremely lightweight at this point, reduced purely to numbers, allowing quick iterations.

# State Tracking & Replays

Next, I would figure out which values to keep track of. I would also need a way to see the best performing run of each major generation by plugging the run back into the original game (using the same seed so the history plays out identically).  
It could also play the game live if desired, with a debugging interface to monitor real-time experiences, rewards, and punishments.

# Reward & Punishment System

Ideally, the AI should be rewarded for actions such as:

* Gaining health  
* Reaching deeper floor levels (main goal)  
* Identifying potions and scrolls (even through usage)  
* Gaining money and collecting items (based on tier and market value, such as inventory-expanding items)  
* Exploring the dungeon  
* Curing negative status effects  
* Upgrading and gaining strength  
* Removing curses (unless it is on a lower-tier unequipped weapon)  
* Using the alchemist system, especially crafting the meat pie

Conversely, it should be punished for:

* Eating early (before starving)  
* Losing health or losing money (to prevent shop loops)  
* Equipping cursed items  
* Wearing items without sufficient current strength

Alternatively, we could simply reward advancing deeper into the dungeon and let the AI figure everything else out independently. Speed in terms of in-game turns could also be rewarded.

# Player Rules & Vision

The AI should be limited to what a human player can do or see. For example, it will always be able to spot regular mimics since they animate even when the player is idle.

# Trainer Interface & Seed Handling

As the trainer, a summary interface should display floor-by-floor point gains and losses, type totals/subtotals, and the overall run score. Numbers should be color-coded in green or red, accompanied by graphs depicting score over turns (annotated with floor transitions) or score over floors.  
Since the dungeon is procedurally generated, generalizing across seeds will be challenging for the AI. To compare runs fairly or display multiple simultaneous simulations (e.g., 1000 sims at once), we can lock the seed, picking the best performing run for a given seed while still training across varied seeds.