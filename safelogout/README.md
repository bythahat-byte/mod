# Safe Logout

Leaves the server when a player on your list comes online (appears in the Tab list).

- You leave a few seconds (3 by default) after they join. If they leave again before then, you stay.
- If they were already online when you joined, you stay. If they log off and come back within 10 seconds
  (a relog), you still stay; if they're gone longer, their next join counts.

```
/safelogout add <name>      add someone
/safelogout remove <name>   remove them
/safelogout delay <seconds> how long to wait after someone joins (default 3)
/safelogout relog <seconds> how long a relog can take without you leaving (default 10)
/safelogout list            your list and who's online
/safelogout on | off | toggle   turn it on or off (or press O; change the key in Controls)
```

The list is saved in `config/safelogout.json`. Only works on servers that show players in the Tab list.
