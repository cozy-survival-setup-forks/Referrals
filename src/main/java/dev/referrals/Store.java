package dev.referrals;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Who was referred, by whom. Kept in data.yml and written whole, through a temporary file, after every change. */
final class Store {

    /** One referral that happened. */
    record Entry(UUID referrer, String referrerName, String name, long at) {
    }

    private final File file;
    private final Map<UUID, Entry> referred = new LinkedHashMap<>();

    Store(File file) {
        this.file = file;
    }

    /** Reads data.yml. A broken file throws, so the caller can stop instead of overwriting it later. */
    void load() throws IOException, InvalidConfigurationException {
        referred.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        ConfigurationSection section = yaml.getConfigurationSection("referred");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) throw new InvalidConfigurationException("referred." + key + " is not a section");
            try {
                referred.put(UUID.fromString(key), new Entry(UUID.fromString(entry.getString("by", "")),
                        entry.getString("by-name", "?"), entry.getString("name", "?"), entry.getLong("at")));
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationException("referred." + key + " has a bad id: " + e.getMessage());
            }
        }
    }

    boolean has(UUID player) {
        return referred.containsKey(player);
    }

    @Nullable Entry get(UUID player) {
        return referred.get(player);
    }

    int countBy(UUID referrer) {
        int count = 0;
        for (Entry entry : referred.values()) {
            if (entry.referrer().equals(referrer)) count++;
        }
        return count;
    }

    /** Records a referral and writes it to disk. If the write fails nothing changes and false is returned. */
    boolean add(UUID player, Entry entry) {
        Entry before = referred.put(player, entry);
        if (save()) return true;
        if (before == null) referred.remove(player);
        else referred.put(player, before);
        return false;
    }

    /** Forgets that a player was referred. False if the write failed, then nothing changed. */
    boolean remove(UUID player) {
        Entry before = referred.remove(player);
        if (before == null) return true;
        if (save()) return true;
        referred.put(player, before);
        return false;
    }

    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> each : referred.entrySet()) {
            String path = "referred." + each.getKey();
            yaml.set(path + ".by", each.getValue().referrer().toString());
            yaml.set(path + ".by-name", each.getValue().referrerName());
            yaml.set(path + ".name", each.getValue().name());
            yaml.set(path + ".at", each.getValue().at());
        }
        Path target = file.toPath();
        Path temporary = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
