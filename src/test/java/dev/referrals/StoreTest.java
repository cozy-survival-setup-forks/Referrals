package dev.referrals;

import dev.referrals.safe.Db;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID NEW = UUID.nameUUIDFromBytes("new".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());
    private static final UUID FRIEND = UUID.nameUUIDFromBytes("friend".getBytes());

    @TempDir
    Path dir;

    private Store open() throws Exception {
        Store store = new Store(dir.resolve("data.yml").toFile(), LOG);
        store.open(3);
        return store;
    }

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("referrals.db").toAbsolutePath();
    }

    private String oldFile() {
        return "referred:\n"
                + "  " + NEW + ":\n    by: " + FRIEND + "\n    by-name: Friend\n    name: Newbie\n    at: 1234\n"
                + "  " + OTHER + ":\n    by: " + FRIEND + "\n    by-name: Friend\n    name: Other\n    at: 5678\n";
    }

    @Test
    void referralsSurviveARestart() throws Exception {
        Store store = open();
        assertTrue(store.add(NEW, new Store.Entry(FRIEND, "Friend", "Newbie", 1234)));
        assertTrue(store.add(OTHER, new Store.Entry(FRIEND, "Friend", "Other", 5678)));
        store.close();

        Store again = open();
        assertTrue(again.has(NEW));
        assertEquals(2, again.countBy(FRIEND));
        assertEquals("Friend", again.get(NEW).referrerName());
        assertEquals(1234, again.get(NEW).at());

        assertTrue(again.remove(NEW));
        assertFalse(again.has(NEW));
        assertEquals(1, again.countBy(FRIEND));
        again.close();

        Store third = open();
        assertFalse(third.has(NEW));
        assertTrue(third.has(OTHER));
        third.close();
    }

    @Test
    void anOldDataFileIsBroughtInOnceAndSetAside() throws Exception {
        Files.writeString(dir.resolve("data.yml"), oldFile());
        Store store = open();
        assertEquals(2, store.countBy(FRIEND));
        assertEquals(5678, store.get(OTHER).at());
        assertFalse(Files.exists(dir.resolve("data.yml")));
        assertTrue(Files.exists(dir.resolve("data.yml.migrated")));
        store.close();

        // a file that shows up later is not read again
        Files.writeString(dir.resolve("data.yml"), "referred: {}\n");
        Store again = open();
        assertEquals(2, again.count());
        again.close();
    }

    @Test
    void aBrokenDataFileStopsTheStartAndIsLeftAlone() throws Exception {
        Files.writeString(dir.resolve("data.yml"), "referred:\n  not-a-uuid:\n    by: x\n");
        assertThrows(InvalidConfigurationException.class, () -> new Store(dir.resolve("data.yml").toFile(), LOG).open(3));

        Files.writeString(dir.resolve("data.yml"), "referred: [unclosed");
        assertThrows(InvalidConfigurationException.class, () -> new Store(dir.resolve("data.yml").toFile(), LOG).open(3));
        assertEquals("referred: [unclosed", Files.readString(dir.resolve("data.yml")));
    }

    @Test
    void aMissingFileIsAnEmptyStore() throws Exception {
        Store store = open();
        assertFalse(store.has(NEW));
        assertEquals(0, store.countBy(FRIEND));
        store.close();
    }

    @Test
    void anImportThatStopsHalfWayLeavesTheFileAndNothingElse() throws Exception {
        Files.writeString(dir.resolve("data.yml"), oldFile());
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE referred (player TEXT PRIMARY KEY, referrer TEXT NOT NULL, referrer_name TEXT NOT NULL, "
                    + "name TEXT NOT NULL, at INTEGER NOT NULL)");
            s.execute("CREATE TRIGGER stop_here BEFORE INSERT ON referred WHEN NEW.name='Other' BEGIN SELECT RAISE(ABORT, 'stopped'); END");
            s.execute("PRAGMA user_version=1");
        }
        Store first = new Store(dir.resolve("data.yml").toFile(), LOG);
        assertThrows(SQLException.class, () -> first.open(3));
        assertTrue(Files.exists(dir.resolve("data.yml")));
        assertEquals(oldFile(), Files.readString(dir.resolve("data.yml")));
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM referred")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "nothing was kept from the half import");
            }
            s.execute("DROP TRIGGER stop_here");
        }
        Store second = open();
        assertEquals(2, second.count());
        assertTrue(Files.exists(dir.resolve("data.yml.migrated")));
        second.close();
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        open().close();
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(dir.resolve("referrals.db"), garbage);
        Store damaged = new Store(dir.resolve("data.yml").toFile(), LOG);
        assertThrows(Db.CorruptException.class, () -> damaged.open(3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(dir.resolve("referrals.db"))));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        Store store = open();
        assertTrue(store.add(NEW, new Store.Entry(FRIEND, "Friend", "Newbie", 1234)));
        assertTrue(store.backup());
        store.close();

        Files.write(dir.resolve("referrals.db"), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("referrals.db-wal"));
        Store restored = open();
        assertTrue(restored.has(NEW));
        restored.close();
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close();
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(dir.resolve("referrals.db"));
        Store store = new Store(dir.resolve("data.yml").toFile(), LOG);
        assertThrows(Db.NewerSchemaException.class, () -> store.open(3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("referrals.db"))));
    }

    @Test
    void aRewardLeftUnfinishedIsFlaggedAndNotRepeated() throws Exception {
        Store store = open();
        String id = store.journal().begin("referral-reward", "referrer=Friend referred=Newbie");
        store.close(); // stopped before the reward was marked as paid

        Store again = open();
        assertEquals(1, again.journal().unknown().size());
        assertTrue(again.journal().resolve(id));
        assertTrue(again.journal().unknown().isEmpty());
        again.close();
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        Store store = open();
        assertNull(store.serverIdSlot().read());
        store.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        store.close();
        Store again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        again.close();
    }

    @Test
    void aFileStoreWithoutADatabaseStillWorksInMemory() {
        File file = dir.resolve("data.yml").toFile();
        Store store = new Store(file, LOG);
        assertTrue(store.add(NEW, new Store.Entry(FRIEND, "Friend", "Newbie", 1)));
        assertTrue(store.has(NEW));
    }
}
