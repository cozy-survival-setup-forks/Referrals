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

`/referral` and `/referrals` work too.

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

- A player can be referred once, ever. The referral is written to `data.yml` before anyone is paid; if the write
  fails nobody is paid, so a restart can never pay the same referral twice.
- You cannot refer yourself, and (by default) not a player who connects from the same address as you.
- One player can bring in at most `max-referrals-per-player` players (10 by default, 0 for no limit).
- Requests have a cooldown, a limit per new player, and end when either player leaves or time runs out.
- If `data.yml` is broken the plugin stops with a clear message instead of overwriting it.
- A reward command that fails is logged and the rest still run.

## Config

`config.yml`: playtime limit, request time, cooldown, limits, the same-address check and the reward commands.
`messages.yml`: every text, in MiniMessage or `&` codes. Referrals are kept in `data.yml`.

## Permissions

- `referrals.use`: send, accept and deny referrals (everybody)
- `referrals.admin`: `/ref info`, `/ref reset` and `/ref reload` (op)

## Telemetry

On startup Referrals sends a small anonymous beacon (plugin name/version, server software/version, online/max
player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with
`metrics.enabled: false` in `config.yml`.

## License

See `LICENSE`: free to run on your own servers, not for redistribution or resale.
