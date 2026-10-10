package dev.referrals;

import dev.referrals.safe.Db;
import dev.referrals.safe.DbBackups;
import dev.referrals.safe.Health;
import dev.referrals.safe.Journal;
import dev.referrals.safe.SafeIo;
import dev.referrals.safe.ServerId;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Who was referred, by whom. Kept in {@code referrals.db} (SQLite): one row per referred player, written in its own
 * transaction as soon as it happens. The older {@code data.yml} is brought in once, in a single transaction, and checked.
 */
final class Store {

    static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "referrals";

    /** One referral that happened. */
    record Entry(UUID referrer, String referrerName, String name, long at) {
    }

    private final File legacy;
    private final Logger log;
    private final Path dbFile;
    private final Path backupDir;
    private final Map<UUID, Entry> referred = new LinkedHashMap<>();

    private Db db;
    private Journal journal;
    private DbBackups backups;
    private boolean imported;

    /** @param legacyFile the old data.yml; the database and the backup folder are beside it */
    Store(File legacyFile, Logger log) {
        this.legacy = legacyFile;
        this.log = log;
        File folder = legacyFile.getAbsoluteFile().getParentFile();
        this.dbFile = folder.toPath().resolve("referrals.db");
        this.backupDir = folder.toPath().resolve("backups");
    }

    /**
     * Opens the database (a damaged one is replaced by the newest backup that verifies), brings in an old data.yml
     * once, and reads everything. Anything that cannot be used throws, so the caller can stop instead of starting empty.
     */
    void open(int backupKeep) throws IOException, SQLException, InvalidConfigurationException {
        db = Db.openRecovering(dbFile, SCHEMA, backupDir, BACKUP_PREFIX, true, log);
        try {
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS referred (player TEXT PRIMARY KEY, referrer TEXT NOT NULL, "
                            + "referrer_name TEXT NOT NULL, name TEXT NOT NULL, at INTEGER NOT NULL)");
                }
            });
            if (db.userVersion() < SCHEMA)
                db.setUserVersion(SCHEMA);
            journal = new Journal(db);
            journal.recover(log);
            importLegacy();
            readAll();
        } catch (IOException | SQLException | InvalidConfigurationException | RuntimeException e) {
            db.close();
            db = null;
            throw e;
        }
        configureBackups(backupKeep);
        Health.file("referrals.db", imported ? "in use, data.yml was brought in" : "in use");
    }

    void configureBackups(int keep) {
        if (db != null)
            backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
    }

    boolean backup() {
        return backups == null || backups.run();
    }

    void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    Journal journal() {
        return journal;
    }

    int count() {
        return referred.size();
    }

    int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    Path databaseFile() {
        return dbFile;
    }

    ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> metaValue(c, "server-id"));
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> putMeta(c, "server-id", id));
            }
        };
    }

    // ---- the working copy

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

    /** Records a referral and writes it. If the write fails nothing changes and false is returned. */
    boolean add(UUID player, Entry entry) {
        if (db == null) {
            referred.put(player, entry);
            return true;
        }
        try {
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO referred (player, referrer, referrer_name, name, at) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT(player) DO UPDATE SET referrer=excluded.referrer, "
                        + "referrer_name=excluded.referrer_name, name=excluded.name, at=excluded.at")) {
                    bind(ps, player, entry);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            log.severe("Could not write referrals.db: " + e.getMessage());
            Health.failure("referrals.db could not be saved: " + e.getMessage());
            return false;
        }
        referred.put(player, entry);
        return true;
    }

    /** Forgets that a player was referred. False if the write failed, then nothing changed. */
    boolean remove(UUID player) {
        if (!referred.containsKey(player)) return true;
        if (db != null) {
            try {
                db.tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM referred WHERE player=?")) {
                        ps.setString(1, player.toString());
                        ps.executeUpdate();
                    }
                });
            } catch (SQLException e) {
                log.severe("Could not write referrals.db: " + e.getMessage());
                Health.failure("referrals.db could not be saved: " + e.getMessage());
                return false;
            }
        }
        referred.remove(player);
        return true;
    }

    private static void bind(PreparedStatement ps, UUID player, Entry entry) throws SQLException {
        ps.setString(1, player.toString());
        ps.setString(2, entry.referrer().toString());
        ps.setString(3, entry.referrerName());
        ps.setString(4, entry.name());
        ps.setLong(5, entry.at());
    }

    private void readAll() throws SQLException {
        referred.clear();
        db.read(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT player, referrer, referrer_name, name, at FROM referred ORDER BY at, player")) {
                while (rs.next()) {
                    referred.put(UUID.fromString(rs.getString(1)),
                            new Entry(UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getLong(5)));
                }
            }
            return null;
        });
    }

    // ---- bringing in data.yml

    private void importLegacy() throws IOException, SQLException, InvalidConfigurationException {
        if (!legacy.exists())
            return;
        if (metaValue("import") != null) {
            setAside(); // brought in earlier; only the rename was left
            return;
        }
        if (db.read(c -> scalar(c, "SELECT COUNT(*) FROM referred")) > 0) {
            log.severe("data.yml is there, but referrals.db already holds referrals and was not made from that file. "
                    + "data.yml was left alone and referrals.db is used.");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(legacy);
        } catch (IOException | InvalidConfigurationException e) {
            // starting empty would let every referral be paid again: the file stays and the plugin stops
            log.severe("data.yml could not be read (" + e.getMessage() + "). It was left where it is, untouched, and Referrals "
                    + "will not start until it is fixed or removed.");
            Health.failure("data.yml could not be read");
            throw e;
        }
        Map<UUID, Entry> parsed = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("referred");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) throw new InvalidConfigurationException("referred." + key + " is not a section");
                try {
                    parsed.put(UUID.fromString(key), new Entry(UUID.fromString(entry.getString("by", "")),
                            entry.getString("by-name", "?"), entry.getString("name", "?"), entry.getLong("at")));
                } catch (IllegalArgumentException e) {
                    throw new InvalidConfigurationException("referred." + key + " has a bad id: " + e.getMessage());
                }
            }
        }
        long wantAt = 0;
        for (Entry e : parsed.values()) wantAt += e.at();
        final long expectAt = wantAt;
        db.tx(c -> {
            // one transaction: a stop in the middle leaves nothing behind and the next start does all of it again
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO referred (player, referrer, referrer_name, name, at) VALUES (?,?,?,?,?)")) {
                for (Map.Entry<UUID, Entry> each : parsed.entrySet()) {
                    bind(ps, each.getKey(), each.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            long n = scalar(c, "SELECT COUNT(*) FROM referred");
            long at = scalar(c, "SELECT COALESCE(SUM(at),0) FROM referred");
            long referrers = scalar(c, "SELECT COUNT(DISTINCT referrer) FROM referred");
            long wantReferrers = parsed.values().stream().map(Entry::referrer).distinct().count();
            if (n != parsed.size() || at != expectAt || referrers != wantReferrers)
                throw new SQLException("what was written does not match data.yml (referrals " + n + "/" + parsed.size()
                        + ", times " + at + "/" + expectAt + ", referrers " + referrers + "/" + wantReferrers + ")");
            putMeta(c, "import", "done " + n + " referrals, " + referrers + " referrers");
        });
        imported = true;
        log.info("data.yml was brought into referrals.db and checked: " + parsed.size() + " referrals.");
        setAside();
    }

    private void setAside() {
        Path from = legacy.toPath();
        Path to = from.resolveSibling(legacy.getName() + ".migrated");
        if (Files.exists(to)) to = from.resolveSibling(legacy.getName() + ".migrated-" + SafeIo.stamp());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            log.info("The old data.yml was renamed to " + to.getFileName() + ". It can be removed once you are happy with referrals.db.");
        } catch (IOException e) {
            log.warning("data.yml could not be renamed (" + e.getMessage() + "); it will not be read again.");
        }
    }

    // ---- small helpers

    private String metaValue(String key) throws SQLException {
        return db.read(c -> metaValue(c, key));
    }

    private static String metaValue(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void putMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
