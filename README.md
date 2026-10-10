# Referrals

Players can refer newly joined players with `/ref <player>`. The new player accepts, and both of them get rewards.
For Paper 1.21+.

## How it works

1. A player runs `/ref <player>` on someone who just joined. The new player must have played for no more than
   `max-playtime-minutes` (5 by default).
2. The new player gets a message with **[Accept]** and **[Deny]** buttons, and has `request-seconds` (120 by default)
   to answer.
3. When they accept, both players get their rewards. A player can only be referred once.

| Command | Use |
| --- | --- |
| `/ref <player>` (or `/ref send <player>`) | Send a referral to a new player |
| `/ref accept <player>` | Accept the referral from that player |
| `/ref deny <player>` | Deny it |
| `/ref info <player>` | Admin: who referred a player, and how many players they brought in |
| `/ref reset <player>` | Admin: let a player be referred again |
| `/ref reload` | Admin: reload `config.yml` and `messages.yml` |

Want other names for the command? Add them to `command-aliases` in `config.yml`, for example `[referral, invite]`, and run `/ref reload`.

## Rewards

Rewards are console commands in `config.yml`, so they work with any economy, key or fly plugin. `%player%` is the
player who gets the reward and `%other%` is the other one. The defaults match a server that gives the referrer
2,500 money, a vote key and 1 minute of temp fly, and the new player 5,000 money, a vote key, 2 minutes of temp fly
and 10 coins. **Change the commands to the ones your plugins use.**

```yaml
rewards:
  referrer:
    - "eco give %player% 2500"
  referred:
    - "eco give %player% 5000"
```

The text players see when they are paid is `accepted-referrer` and `accepted-referred` in `messages.yml`. Update it
when you change the rewards.

## Safe by design

- A player can be referred once, ever. The referral is written to `referrals.db` before anyone is paid; if the write
  fails nobody is paid, so a restart can never pay the same referral twice.
- A player must have played for `min-referrer-playtime-minutes` (30 by default) before they can refer anyone, so a brand new account cannot refer its own second account.
- You cannot refer yourself, and (by default) not a player who connects from the same address as you.
- One player can bring in at most `max-referrals-per-player` players (10 by default, 0 for no limit).
- Requests have a cooldown, a limit per new player, and end when either player leaves or time runs out.
- Referrals are kept in `referrals.db` (SQLite). An older `data.yml` is brought in once at the first start, in one step and checked (the number of referrals, the referrers and the times must match), and then renamed to `data.yml.migrated`. If it is broken, or the database is damaged with no usable backup, the plugin stops with a clear message instead of starting empty and paying again.
- The reward of a referral is written to a record before it is paid. If the server stops in the middle, it is not paid a second time: it is listed in `/ref doctor` and the console, and `/ref doctor resolve <id>` clears it after you have checked.
- A reward command that fails is logged and the rest still run.

## Config

`config.yml`: extra command names, playtime limits, request time, cooldown, limits, the same-address check and the reward commands.
`messages.yml`: every text, in MiniMessage or `&` codes. Referrals are kept in `referrals.db`.

## Permissions

- `referrals.use`: send, accept and deny referrals (everybody)
- `referrals.admin`: `/ref info`, `/ref reset` and `/ref reload` (op)

## Keeping your files safe

- `config.yml` and `messages.yml` start with a `config-version` / `lang-version` number. After an update, new settings are added to your files with their comments, and nothing you changed is touched. The old file is kept next to it as `<name>.<date>.bak` (the newest 5). A setting is only removed when the changelog says so.
- A value with a mistake (a negative time, an item that does not exist, text where a number belongs) is named in the console by file and key. On a reload, the settings in use stay as they were.
- Files are written to a temporary file and moved into place, with the previous version kept as `.bak`. A file that cannot be read is restored from its `.bak`, and the unreadable one is kept as `.broken-<time>`.
- A file or database that was made by a newer version of the plugin is left alone and a warning is logged.
- `referrals.db` is a SQLite database in WAL mode. It is checked when the plugin starts, a copy is made on a schedule (`backup.interval-hours`, `backup.keep` in `config.yml`, in the `backups` folder) and every copy is opened and checked before older ones are removed. A damaged database is replaced by the newest copy that checks out, or, where nothing may be lost, the plugin stays off and the file is left untouched.
- The backup is a consistent snapshot, not a copy of the open file.
- `/ref doctor` shows the health of the files, versions, last backup and recent save failures (no player data). `/ref backup now` makes a checked backup right away. Both need the admin permission.

## Telemetry

On startup Referrals sends a small anonymous beacon (plugin name/version, server software/version, online/max
player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with
`metrics.enabled: false` in `config.yml`. The random ID is kept as `server-id` in `referrals.db` (older versions kept it in a `.server-id` file, which is moved over unchanged). The address and the interval are fixed in the plugin and are not settings.

## License

See `LICENSE`: free to run on your own servers, not for redistribution or resale.
