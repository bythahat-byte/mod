# Safe Logout

Leaves the server as soon as a player on your enemy list comes online (appears in the Tab list).

- If an enemy is **already online when you join**, you stay. You'll get a yellow message saying so.
- If that enemy **logs off and comes back**, or **anyone else on your list joins**, you leave straight away.

```
/safelogout add <name>      add an enemy
/safelogout remove <name>   remove one
/safelogout admin add <name>     add an admin (you leave a few seconds after they join)
/safelogout admin remove <name>
/safelogout admin delay <seconds>   how long to wait after an admin joins (default 3)
/safelogout admin relog <seconds>   an admin who was already on can relog within this long without you leaving (default 10)
/safelogout list            who's on your lists and who's online
/safelogout on | off | toggle   turn it on or off (or press O; change the key in Controls)
```

The list is saved in `config/safelogout.json`. Only works on servers that show players in the Tab list.
