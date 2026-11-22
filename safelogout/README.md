# Safe Logout

Leaves the server as soon as a player on your enemy list comes online (appears in the Tab list).

- If an enemy is **already online when you join**, you stay. You'll get a yellow message saying so.
- If that enemy **logs off and comes back**, or **anyone else on your list joins**, you leave straight away.

```
/safelogout add <name>      add an enemy
/safelogout remove <name>   remove one
/safelogout list            who's on your list and who's online
/safelogout on | off        turn it on or off
```

The list is saved in `config/safelogout.json`. Only works on servers that show players in the Tab list.
