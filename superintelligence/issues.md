# Viewer / recordings

1. When using replay-viewer.bat, after a recording finishes and the user presses 'R', after reset the recording still says finished. When the user then tries to press spacebar to continue, the game thinks it's the waiting keybind and the recording doesn't play, instead diverges due to wait action.

2. When using replay-viewer.bat, the viewer doesn't actually play out the last step where the hero dies. Instead it finishes just before that.

3. Turns and turn limit is a bit misleading, since it's actions, as turns in the engine are often not whole numbers and certain items and conditions can affect the amount of turns an action takes.

4. The recordings are missing information about how the score changes with each action. Very important to see in the viewer as a live info. Additionally there should be a way to see total score gained and lost as well, not just their sum.

5. Recordings show coordinates (such as during diverted) as a single number. It'd be much easier for a human to troubleshoot if they were in x,y. With x increasing to the right of the screen and y increasing with height.

# Env

6. I am unsure if the env.gid dimensions are accurate. I read online that the max is 32x32. This number might've changed but a code sweep might be in order.

7. The inventory slots / inventory system seems wrong. 

"Each standard dungeon floor in Shattered Pixel Dungeon is hardcoded to a grid size of 32x32 tiles.   

While the absolute boundaries of the floor remain locked at 32x32, the playable layout is procedurally generated within that space, leaving some of the grid as unused wall space. The rooms that generate within this grid fall into three primary size categories:   

    Normal rooms: Ranging from 2x2 to 8x8 tiles.

    Large rooms: Ranging from 8x8 to 12x12 tiles.

    Giant rooms: Ranging from 12x12 to 16x16 tiles.

What is the max inventory space available with all of the expansions?

The maximum inventory space available in Shattered Pixel Dungeon is 96 item slots when you have acquired all of the expansion containers.

Your starting Backpack provides 20 item slots for general items. The game features four specialized expansion bags, each providing an additional 19 slots for specific item categories:   

    Velvet Pouch: 19 slots for seeds, runestones, goo blobs, and cursed shards.   

Scroll Holder: 19 slots for scrolls, exotic scrolls, spells, and arcane catalysts.   

Potion Bandolier: 19 slots for potions, exotic potions, brews, elixirs, and alchemical catalysts.   

Magical Holster: 19 slots for thrown weapons, wands, and bombs.   

Certain items do not take up inventory space. Gold coins, keys, energy crystals, the adventurer's journal, and the containers themselves can be held in unlimited quantities without occupying any of your 96 main slots."

# Training

8. I saw a hero pick up and drop an item in a recording, presumably cheating score. This has not yet been confirmed though. I don't see configuration for such things in the properties file.
