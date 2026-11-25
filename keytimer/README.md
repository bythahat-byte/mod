# Key Timer

Fabric client mod for Minecraft 1.21.11. Presses a list of keys in order, waiting a set time after
each: for example `1`, wait 60 s, `2`, wait 60 s, then (optionally) starts over.

```
/keytimer add 1 60      press 1, then wait 60 seconds
/keytimer add 2 60      press 2, then wait 60 seconds
/keytimer hold d 15 15  hold d for 15 seconds, then wait 15 seconds
/keytimer hold w 5 0    hold w for 5 seconds, then go straight to the next step
/keytimer move d 6 5    walk exactly 6 blocks with d (stops on the block centre), then wait 5 seconds
/keytimer wait 10       just pause for 10 seconds
/keytimer hold w+d 5 0  hold several keys together (joined with +)
/keytimer move w+d 200 0   walk 200 blocks holding W and D (e.g. hugging a wall at an angle)
/keytimer look -114 0      face yaw -114 (the first F3 "Facing" number); look -114 6.4 0 also sets pitch
/keytimer drop named "Cow Curds" 0   open the inventory and drop every stack with that name
/keytimer stash named Rennet 0      move every Rennet stack from the hotbar into the inventory
/keytimer chest named Rennet 0.5 12 open the chest you're looking at and take Rennet until you carry 12 stacks
/keytimer deposit named "Cow Curds" 0.3   open the chest/barrel you're looking at and put every Cow Curds stack in it
/keytimer edit 1 look here 0.3      make step 1 face wherever you're looking right now
/keytimer flick 40 50 1.5 0         keep moving your head to a random pitch between 40 and 50 (about every 1.5s)
/keytimer flick off 0               stop the head flick
/keytimer clicker right 15 0        keep right-clicking 15 times a second while the next steps run
/keytimer clicker off 0             stop clicking
/keytimer leavewhenout hay_block    while running, leave the server when you have no hay bales left (saved with each save)
/keytimer leavewhenout off          turn that off
/keytimer start 5 10                load save 5 and start at step 10 (skip steps you've already done)
/keytimer startat 10                start the loaded list at step 10
/keytimer select "bucket|Sheep's Milk|Cow's Milk" 0   switch to the first hotbar slot holding one of these
/keytimer slot 1 "Cow's Milk|Sheep's Milk|bucket" 0   put the first one found into hotbar slot 1 and select slot 1
/keytimer goto w+a x 113912.5 0     hold W+A until your X (F3) reaches 113912.5, or you walk into a wall
/keytimer refill wheat 1   open the inventory and move all wheat to the hotbar, then wait 1 second
/keytimer crafthay 1       craft hay bales into wheat (only while there's room), then wait 1 second
/keytimer crafthay 1 1     same, but only one stack of hay bales
/keytimer crafthay 1 1 64  only when you have less than 64 wheat (and room for the whole stack's wheat)
/keytimer refill wheat 1 2 move only 2 stacks of wheat to the hotbar
/keytimer refill named Rennet 1       move items by the name shown on them (custom server items)
/keytimer refill named "Raw Milk" 1   names with spaces go in quotes
/keytimer repeat 11 2   make the last 2 steps happen 11 times in total
/keytimer start         (or bind "Start/Stop Key Timer" in Controls)
/keytimer stop
/keytimer               list the steps
/keytimer edit 16 hold s 2.3 0     replace step 16 with a new step (hold/add/wait forms)
/keytimer insert 16 wait 2         put a new step before step 16
/keytimer remove 2      remove step 2
/keytimer clear         remove all steps
/keytimer loop false    run the list once instead of repeating
```

Keys: `1`-`9`, `a`-`z`, `space`, `left`/`right`/`middle` (mouse buttons), or any Minecraft key name
such as `key.keyboard.f6`. The press acts like you pressing the key, so it does whatever that key is
bound to. Steps are saved in `config/keytimer.json`. It stops when you leave the world.
Some servers don't allow macros; check their rules.

## Saves

```
/keytimer save 1        save the current list as "1" (any name works, e.g. cowfarm)
/keytimer saves         list your saves
/keytimer load 1        replace the current list with save "1"
/keytimer start 1       load save "1" and start it
/keytimer delete 1      delete save "1"
```
