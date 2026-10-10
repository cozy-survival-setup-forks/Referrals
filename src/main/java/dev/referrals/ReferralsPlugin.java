package dev.referrals;

import dev.referrals.Requests.Added;
import dev.referrals.Rules.Verdict;
import dev.referrals.safe.ConfigMigrator;
import dev.referrals.safe.Doctor;
import dev.referrals.safe.FileBackups;
import dev.referrals.safe.Guard;
import dev.referrals.safe.Health;
import dev.referrals.safe.Journal;
import dev.referrals.safe.Prep;
import dev.referrals.safe.ServerId;
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
import java.sql.SQLException;
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
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("info", "reset", "reload", "doctor", "backup");

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("max-playtime-minutes", 1, 525_600);
                rules.range("min-referrer-playtime-minutes", 0, 525_600);
                rules.range("request-seconds", 5, 86_400);
                rules.range("send-cooldown-seconds", 0, 3600);
                rules.range("max-pending-requests", 1, 100);
                rules.range("max-referrals-per-player", 0, 1_000_000);
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }),
            new Prep.Spec("messages.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private Settings settings;
    private Messages messages;
    private Store store;
    private final Requests requests = new Requests();
    private final List<Command> aliasCommands = new ArrayList<>();

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
        Health.storage("SQLite referrals.db for who was referred by whom; YAML for config.yml and messages.yml");
        Prep.startup(this, files);
        reloadConfig();
        settings = new Settings(getConfig());
        messages = new Messages(this);
        if (!messages.load()) {
            getLogger().severe("messages.yml is broken, disabling Referrals. Fix the file, or delete it to get the default one.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        store = new Store(new File(getDataFolder(), "data.yml"), getLogger());
        try {
            store.open(backupKeep());
        } catch (IOException | SQLException | InvalidConfigurationException e) {
            // Stopping keeps the data as it is, so it can be fixed by hand. Carrying on empty would let every referral be paid again.
            getLogger().log(Level.SEVERE, "The referral data cannot be used, so Referrals is switching itself off and nothing is reset or paid twice: " + e.getMessage());
            Health.failure("referral data could not be opened: " + e.getMessage());
            store = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("ref");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        registerAliases();
        Bukkit.getScheduler().runTaskTimer(this, this::sweep, 100L, 100L);

        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), store.serverIdSlot(), beacon, getLogger()));
        scheduleBackups();
        Banner.print(this, "Thanks for bringing new players home.");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        unregisterAliases();
        if (store != null) store.close();
    }

    private int backupKeep() {
        return Math.max(1, Math.min(90, getConfig().getInt("backup.keep", 7)));
    }

    private org.bukkit.scheduler.BukkitTask backupTask;

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups() {
        int hours = Math.max(1, Math.min(168, getConfig().getInt("backup.interval-hours", 6)));
        store.configureBackups(backupKeep());
        if (backupTask != null) backupTask.cancel();
        backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, store::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /ref doctor. */
    private List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Referral database: referrals.db, schema " + store.schemaVersion() + " (this plugin writes " + Store.SCHEMA + ")");
        extra.add("Stored referrals: " + store.count());
        String newest = store.newestBackup();
        extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        extra.add("Pending writes: 0 (every referral is written as it happens)");
        try {
            List<String> unknown = store.journal() == null ? List.of() : store.journal().unknown();
            extra.add("Rewards that may or may not have been paid (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /ref doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Reward record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    /** /ref backup now: a checked copy of the database and of the settings files. */
    private boolean backupNow() {
        boolean database = store.backup();
        boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), new ArrayList<>(Prep.fileNames(files)), 5, getLogger());
        return database && settingsFiles;
    }

    /** The command names from command-aliases, each one a command of its own that does what /ref does. */
    private void registerAliases() {
        for (String alias : settings.aliases) {
            Command extra = new Command(alias) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                    return onCommand(sender, this, label, args);
                }

                @Override
                public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                    return onTabComplete(sender, this, label, args);
                }
            };
            extra.setDescription("Same as /ref");
            if (!Bukkit.getCommandMap().register(getName().toLowerCase(Locale.ROOT), extra)) {
                // Another plugin owns that name; ours is only reachable as referrals:<name>.
                getLogger().warning("command-aliases: /" + alias + " is already used by another command, so it is only available as /referrals:" + alias);
            }
            aliasCommands.add(extra);
        }
    }

    private void unregisterAliases() {
        var known = Bukkit.getCommandMap().getKnownCommands();
        for (Command extra : aliasCommands) {
            extra.unregister(Bukkit.getCommandMap());
            known.values().removeIf(each -> each == extra);
        }
        aliasCommands.clear();
    }

    private void refreshCommands() {
        for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
    }

    /** Reads config.yml and messages.yml again. If one is broken the old settings stay. */
    private boolean reload() {
        List<Guard.Problem> problems = Prep.validate(this, files);
        if (!problems.isEmpty()) {
            Prep.logRejected(this, problems);
            return false;
        }
        try {
            File file = new File(getDataFolder(), "config.yml");
            var loaded = new org.bukkit.configuration.file.YamlConfiguration();
            loaded.load(file);
            Settings fresh = new Settings(loaded);
            if (!messages.load()) return false;
            reloadConfig();
            scheduleBackups();
            settings = fresh;
            unregisterAliases();
            registerAliases();
            refreshCommands();
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
            case "info", "reset", "reload", "doctor", "backup" -> admin(sender, first, args);
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

        if (!target.hasPermission("referrals.use")) {
            messages.send(sender, "refused-target-cannot", "player", target.getName());
            return;
        }
        Verdict verdict = Rules.check(sender.getUniqueId().equals(target.getUniqueId()),
                target.getStatistic(Statistic.PLAY_ONE_MINUTE), settings.maxPlayedTicks,
                store.has(target.getUniqueId()), sender.getStatistic(Statistic.PLAY_ONE_MINUTE), settings.minReferrerTicks,
                store.countBy(sender.getUniqueId()), settings.limit, settings.blockSameAddress && sameAddress(sender, target));
        if (verdict != Verdict.OK) {
            messages.send(sender, "refused-" + verdict.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    "player", target.getName(), "minutes", String.valueOf(settings.maxPlayedTicks / 1200),
                    "minutes-needed", String.valueOf(settings.minReferrerTicks / 1200), "limit", String.valueOf(settings.limit));
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
                sender.getStatistic(Statistic.PLAY_ONE_MINUTE), settings.minReferrerTicks,
                store.countBy(sender.getUniqueId()), settings.limit, settings.blockSameAddress && sameAddress(sender, target));
        if (verdict != Verdict.OK) {
            messages.send(target, "accept-refused-target");
            messages.send(sender, "accept-refused-sender", "player", target.getName());
            return;
        }

        // Written down and saved first: if it cannot be written, nobody is paid, so a restart can never pay the same
        // referral twice, and a payout that was cut short is listed for a person to check.
        Journal journal = store.journal();
        String record = null;
        try {
            if (journal != null) {
                record = journal.begin("referral-reward", "referrer=" + sender.getName() + " (" + sender.getUniqueId() + ") referred="
                        + target.getName() + " (" + target.getUniqueId() + ")");
            }
        } catch (SQLException e) {
            getLogger().severe("The reward record could not be written, so the referral of " + target.getName() + " by " + sender.getName() + " was not paid: " + e.getMessage());
            messages.send(target, "save-failed");
            messages.send(sender, "save-failed");
            return;
        }
        if (!store.add(target.getUniqueId(), new Store.Entry(sender.getUniqueId(), sender.getName(), target.getName(), now))) {
            finishRecord(journal, record, "referral could not be saved", false);
            getLogger().severe("Could not write referrals.db, so the referral of " + target.getName() + " by " + sender.getName() + " was not paid.");
            messages.send(target, "save-failed");
            messages.send(sender, "save-failed");
            return;
        }
        requests.clearTarget(target.getUniqueId());

        run(settings.referrerCommands, sender, target);
        run(settings.referredCommands, target, sender);
        finishRecord(journal, record, null, true);
        messages.send(sender, "accepted-referrer", "player", target.getName());
        messages.send(target, "accepted-referred", "player", sender.getName());
        sender.playSound(sender.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void finishRecord(Journal journal, String record, String reason, boolean ok) {
        if (journal == null || record == null) return;
        try {
            if (ok) journal.succeeded(record);
            else journal.failed(record, reason);
        } catch (SQLException e) {
            getLogger().warning("The reward record could not be finished: " + e.getMessage());
        }
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
        if (what.equals("doctor")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("resolve")) {
                boolean done = false;
                try {
                    done = store.journal() != null && store.journal().resolve(args[2]);
                } catch (SQLException e) {
                    getLogger().severe("Could not update the reward record: " + e.getMessage());
                }
                sender.sendPlainMessage(done ? "Marked as checked." : "No unfinished reward has that id.");
            } else {
                doctor().forEach(sender::sendPlainMessage);
            }
            return;
        }
        if (what.equals("backup")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("now")) sender.sendPlainMessage("Use /ref backup now");
            else sender.sendPlainMessage(backupNow() ? "Backup made and checked." : "The backup FAILED, see the console.");
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
