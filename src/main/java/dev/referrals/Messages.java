package dev.referrals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts in messages.yml, written in MiniMessage. Old style codes such as &amp;7 and &amp;#RRGGBB work too.
 * Placeholders are written as {@code <time>}.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"), Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"), Map.entry('6', "gold"), Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"), Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"), Map.entry('f', "white"),
            Map.entry('k', "obfuscated"), Map.entry('l', "bold"), Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"), Map.entry('o', "italic"), Map.entry('r', "reset"));

    private final ReferralsPlugin plugin;
    private FileConfiguration file = new YamlConfiguration();

    public Messages(ReferralsPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return false if messages.yml is broken, in which case the messages already loaded are kept */
    public boolean load() {
        File target = new File(plugin.getDataFolder(), "messages.yml");
        if (!target.exists()) plugin.saveResource("messages.yml", false);

        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(target);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("messages.yml is broken, keeping the messages already loaded: " + e.getMessage());
            return false;
        }

        // Messages added in newer versions still work with an older file.
        try (var defaults = plugin.getResource("messages.yml")) {
            if (defaults != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the default messages.yml: " + e.getMessage());
        }
        file = loaded;
        return true;
    }

    /** {@code file.getString(key, "")} would never fall back to a bundled default even after setDefaults - the
     * two-argument getString only ever returns the explicit default when the key is unset, it never consults
     * the defaults. The one-argument form does. */
    private String text(String key) {
        String value = file.getString(key);
        return value == null ? "" : value;
    }

    /** Turns old style codes into MiniMessage tags. */
    public static String convertLegacy(String text) {
        String result = HEX.matcher(text).replaceAll("<#$1>");
        return CODE.matcher(result).replaceAll(match -> Matcher.quoteReplacement(
                "<" + TAGS.get(Character.toLowerCase(match.group(1).charAt(0))) + ">"));
    }

    /** {@code pairs} are placeholder names and values: "time", "5s", "player", "Steve". */
    public Component parse(String text, String... pairs) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            resolvers.add(Placeholder.parsed(pairs[i], pairs[i + 1]));
        }
        return MINI.deserialize(convertLegacy(text), resolvers.toArray(new TagResolver[0]));
    }

    public Component component(String key, String... pairs) {
        return parse(text(key), pairs);
    }

    /** Sends a message with the prefix. An empty message is skipped, so any of them can be turned off. */
    public void send(CommandSender to, String key, String... pairs) {
        if (file.isList(key)) {
            for (String line : file.getStringList(key)) to.sendMessage(parse(line, pairs));
            return;
        }
        String message = text(key);
        if (message.isEmpty()) return;
        to.sendMessage(parse(text("prefix") + message, pairs));
    }

    public String raw(String key) {
        return text(key);
    }

    public List<String> lines(String key) {
        return file.getStringList(key);
    }
}
