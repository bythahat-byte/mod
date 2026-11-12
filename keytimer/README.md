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
