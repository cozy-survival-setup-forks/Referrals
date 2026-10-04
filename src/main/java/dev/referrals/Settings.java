package dev.referrals;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** config.yml, read once per reload. */
final class Settings {

    final long maxPlayedTicks;
    final long minReferrerTicks;
    final long requestMillis;
    final long cooldownMillis;
    final int maxPending;
    final int limit;
    final boolean blockSameAddress;
    final List<String> aliases;
    final List<String> referrerCommands;
    final List<String> referredCommands;

    Settings(FileConfiguration config) {
        maxPlayedTicks = Math.max(1, Math.min(config.getLong("max-playtime-minutes", 5), 60L * 24 * 365)) * 60L * 20L;
        minReferrerTicks = Math.max(0, Math.min(config.getLong("min-referrer-playtime-minutes", 30), 60L * 24 * 365)) * 60L * 20L;
        requestMillis = Math.max(5, Math.min(config.getLong("request-seconds", 120), 86_400L)) * 1000L;
        cooldownMillis = Math.max(0, Math.min(config.getLong("send-cooldown-seconds", 10), 3600L)) * 1000L;
        maxPending = Math.max(1, Math.min(config.getInt("max-pending-requests", 5), 100));
        limit = Math.max(0, config.getInt("max-referrals-per-player", 10));
        blockSameAddress = config.getBoolean("block-same-address", true);
        aliases = aliases(config.getStringList("command-aliases"));
        referrerCommands = commands(config.getStringList("rewards.referrer"));
        referredCommands = commands(config.getStringList("rewards.referred"));
    }

    /** The extra names of the command: lower case, no slash, letters, digits, - and _ only, no repeats, not "ref". */
    static List<String> aliases(List<String> names) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            String alias = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (alias.startsWith("/")) alias = alias.substring(1);
            if (alias.matches("[a-z0-9_-]{1,32}") && !alias.equals("ref") && !result.contains(alias)) result.add(alias);
        }
        return List.copyOf(result);
    }

    private static List<String> commands(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String command = line.trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (!command.isEmpty()) result.add(command);
        }
        return List.copyOf(result);
    }
}
