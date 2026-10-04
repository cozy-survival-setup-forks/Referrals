package dev.referrals;

import dev.referrals.Requests.Added;
import dev.referrals.Rules.Verdict;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Players send a referral request to someone who just joined. When the new player accepts, both get their rewards.
 * See the README.
 */
public final class ReferralsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("accept", "deny", "send");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("info", "reset", "reload");

    private Settings settings;
    private Messages messages;
    private Store store;
    private final Requests requests = new Requests();

    @Override
    public void onEnable() {
        try {
            enableInner();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Referrals could not start, check config.yml and messages.yml for mistakes", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void enableInner() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        messages = new Messages(this);
        messages.load();

        store = new Store(new File(getDataFolder(), "data.yml"));
        try {
            store.load();
        } catch (IOException | InvalidConfigurationException e) {
            // Stopping keeps the broken file as it is, so it can be fixed by hand. Carrying on would overwrite it.
            getLogger().log(Level.SEVERE, "data.yml is broken, disabling Referrals so it is not overwritten. Fix or move the file.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("referral");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::sweep, 100L, 100L);

        Banner.print(this, "Thanks for bringing new players home.");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
    }

    /** Reads config.yml and messages.yml again. If one is broken the old settings stay. */
    private boolean reload() {
        try {
            File file = new File(getDataFolder(), "config.yml");
            var loaded = new org.bukkit.configuration.file.YamlConfiguration();
            loaded.load(file);
            Settings fresh = new Settings(loaded);
            if (!messages.load()) return false;
            reloadConfig();
            settings = fresh;
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml is broken, keeping the settings as they were: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "accept", "deny" -> answer(sender, args, first.equals("accept"));
            case "send" -> send(sender, args.length > 1 ? args[1] : null);
            case "info", "reset", "reload" -> admin(sender, first, args);
            case "" -> messages.send(sender, sender.hasPermission("referrals.admin") ? "usage-admin" : "usage");
            default -> send(sender, args[0]);
        }
        return true;
    }

    private void send(CommandSender who, String name) {
        if (!(who instanceof Player sender)) {
            messages.send(who, "players-only");
            return;
        }
        if (!sender.hasPermission("referrals.use")) {
            messages.send(sender, "no-permission");
            return;
        }
        Player target = name == null ? null : Bukkit.getPlayerExact(name);
        if (target == null) {
            messages.send(sender, "player-not-found", "player", name == null ? "?" : name);
            return;
        }

        Verdict verdict = Rules.check(sender.getUniqueId().equals(target.getUniqueId()),
                target.getStatistic(Statistic.PLAY_ONE_MINUTE), settings.maxPlayedTicks,
                store.has(target.getUniqueId()), store.countBy(sender.getUniqueId()), settings.limit,
                settings.blockSameAddress && sameAddress(sender, target));
        if (verdict != Verdict.OK) {
            messages.send(sender, "refused-" + verdict.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    "player", target.getName(), "minutes", String.valueOf(settings.maxPlayedTicks / 1200), "limit", String.valueOf(settings.limit));
            return;
        }

        Added added = requests.add(sender.getUniqueId(), target.getUniqueId(), System.currentTimeMillis(),
                settings.requestMillis, settings.cooldownMillis, settings.maxPending);
        if (added != Added.ADDED) {
            messages.send(sender, "request-" + added.name().toLowerCase(Locale.ROOT).replace('_', '-'), "player", target.getName());
            return;
        }

        String seconds = String.valueOf(settings.requestMillis / 1000);
        messages.send(sender, "request-sent", "player", target.getName(), "seconds", seconds);
        messages.send(target, "request-received", "player", sender.getName(), "seconds", seconds);
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    private void answer(CommandSender who, String[] args, boolean accept) {
        if (!(who instanceof Player target)) {
            messages.send(who, "players-only");
            return;
        }
        if (!target.hasPermission("referrals.use")) {
            messages.send(target, "no-permission");
            return;
        }
        Player sender = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        long now = System.currentTimeMillis();
        if (sender == null || !requests.take(target.getUniqueId(), sender.getUniqueId(), now)) {
            messages.send(target, "no-request", "player", args.length > 1 ? args[1] : "?");
            return;
        }

        if (!accept) {
            messages.send(target, "denied-you", "player", sender.getName());
            messages.send(sender, "denied-them", "player", target.getName());
            return;
        }

        // The rules are checked again: a lot can happen while a request waits.
        Verdict verdict = Rules.check(false, 0, settings.maxPlayedTicks, store.has(target.getUniqueId()),
                store.countBy(sender.getUniqueId()), settings.limit, settings.blockSameAddress && sameAddress(sender, target));
        if (verdict != Verdict.OK) {
            messages.send(target, "refused-" + verdict.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    "player", sender.getName(), "minutes", String.valueOf(settings.maxPlayedTicks / 1200), "limit", String.valueOf(settings.limit));
            return;
        }

        // Saved first: if it cannot be written, nobody is paid, so a restart can never pay the same referral twice.
        if (!store.add(target.getUniqueId(), new Store.Entry(sender.getUniqueId(), sender.getName(), target.getName(), now))) {
            getLogger().severe("Could not write data.yml, so the referral of " + target.getName() + " by " + sender.getName() + " was not paid.");
            messages.send(target, "save-failed");
            messages.send(sender, "save-failed");
            return;
        }
        requests.clearTarget(target.getUniqueId());

        run(settings.referrerCommands, sender, target);
        run(settings.referredCommands, target, sender);
        messages.send(sender, "accepted-referrer", "player", target.getName());
        messages.send(target, "accepted-referred", "player", sender.getName());
        sender.playSound(sender.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    /** Runs reward commands from the console. One that fails does not stop the others. */
    private void run(List<String> commands, Player receiver, Player other) {
        for (String command : commands) {
            String line = command.replace("%player%", receiver.getName()).replace("%other%", other.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "The reward command '" + line + "' failed", e);
            }
        }
    }

    private static boolean sameAddress(Player a, Player b) {
        InetSocketAddress first = a.getAddress();
        InetSocketAddress second = b.getAddress();
        return first != null && second != null && first.getAddress().equals(second.getAddress());
    }

    private void admin(CommandSender sender, String what, String[] args) {
        if (!sender.hasPermission("referrals.admin")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (what.equals("reload")) {
            messages.send(sender, reload() ? "reloaded" : "reload-failed");
            return;
        }
        String name = args.length > 1 ? args[1] : null;
        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer player = name == null ? null : Bukkit.getOfflinePlayerIfCached(name);
        if (player == null) {
            messages.send(sender, "player-not-found", "player", name == null ? "?" : name);
            return;
        }

        if (what.equals("reset")) {
            if (!store.has(player.getUniqueId())) {
                messages.send(sender, "info-none", "player", String.valueOf(player.getName()));
            } else {
                messages.send(sender, store.remove(player.getUniqueId()) ? "reset-done" : "save-failed", "player", String.valueOf(player.getName()));
            }
            return;
        }

        Store.Entry entry = store.get(player.getUniqueId());
        messages.send(sender, entry == null ? "info-none" : "info-referred", "player", String.valueOf(player.getName()),
                "referrer", entry == null ? "" : entry.referrerName(),
                "date", entry == null ? "" : java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(entry.at()), java.time.ZoneId.systemDefault()).toString());
        messages.send(sender, "info-count", "player", String.valueOf(player.getName()), "count", String.valueOf(store.countBy(player.getUniqueId())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
            if (sender.hasPermission("referrals.admin")) options.addAll(ADMIN_SUBCOMMANDS);
            for (Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
        } else if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if ((first.equals("accept") || first.equals("deny")) && sender instanceof Player player) {
                for (UUID id : requests.pending(player.getUniqueId(), System.currentTimeMillis())) {
                    Player from = Bukkit.getPlayer(id);
                    if (from != null) options.add(from.getName());
                }
            } else if (first.equals("send") || (sender.hasPermission("referrals.admin") && (first.equals("info") || first.equals("reset")))) {
                for (Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requests.forget(event.getPlayer().getUniqueId());
    }

    /** Tells the senders whose request ran out. */
    private void sweep() {
        for (Requests.Expired expired : requests.sweep(System.currentTimeMillis(), settings.cooldownMillis)) {
            Player sender = Bukkit.getPlayer(expired.sender());
            Player target = Bukkit.getPlayer(expired.target());
            if (sender != null) messages.send(sender, "request-expired", "player", target == null ? "?" : target.getName());
        }
    }
}
