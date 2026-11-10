# Key Timer

Fabric client mod for Minecraft 1.21.11. Presses a list of keys in order, waiting a set time after
each: for example `1`, wait 60 s, `2`, wait 60 s, then (optionally) starts over.

```
/keytimer add 1 60      press 1, then wait 60 seconds
/keytimer add 2 60      press 2, then wait 60 seconds
/keytimer hold d 15 15  hold d for 15 seconds, then wait 15 seconds
/keytimer hold w 5 0    hold w for 5 seconds, then go straight to the next step
/keytimer wait 10       just pause for 10 seconds
/keytimer start         (or bind "Start/Stop Key Timer" in Controls)
/keytimer stop
/keytimer               list the steps
/keytimer remove 2      remove step 2
/keytimer clear         remove all steps
/keytimer loop false    run the list once instead of repeating
```

Keys: `1`-`9`, `a`-`z`, `space`, `left`/`right`/`middle` (mouse buttons), or any Minecraft key name
such as `key.keyboard.f6`. The press acts like you pressing the key, so it does whatever that key is
bound to. Steps are saved in `config/keytimer.json`. It stops when you leave the world.
Some servers don't allow macros; check their rules.
