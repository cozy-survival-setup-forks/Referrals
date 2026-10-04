package dev.referrals;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    private static final UUID NEW = UUID.nameUUIDFromBytes("new".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());
    private static final UUID FRIEND = UUID.nameUUIDFromBytes("friend".getBytes());

    @Test
    void referralsSurviveARestart(@TempDir Path dir) throws Exception {
        File file = dir.resolve("data.yml").toFile();
        Store store = new Store(file);
        store.load();
        assertTrue(store.add(NEW, new Store.Entry(FRIEND, "Friend", "Newbie", 1234)));
        assertTrue(store.add(OTHER, new Store.Entry(FRIEND, "Friend", "Other", 5678)));

        Store again = new Store(file);
        again.load();
        assertTrue(again.has(NEW));
        assertEquals(2, again.countBy(FRIEND));
        assertEquals("Friend", again.get(NEW).referrerName());
        assertEquals(1234, again.get(NEW).at());

        assertTrue(again.remove(NEW));
        assertFalse(again.has(NEW));
        assertEquals(1, again.countBy(FRIEND));
    }

    @Test
    void aBrokenFileIsReportedNotOverwritten(@TempDir Path dir) throws Exception {
        File file = dir.resolve("data.yml").toFile();
        Files.writeString(file.toPath(), "referred:\n  not-a-uuid:\n    by: x\n");
        assertThrows(InvalidConfigurationException.class, () -> new Store(file).load());

        Files.writeString(file.toPath(), "referred: [unclosed");
        assertThrows(InvalidConfigurationException.class, () -> new Store(file).load());
        assertEquals("referred: [unclosed", Files.readString(file.toPath()));
    }

    @Test
    void aMissingFileIsAnEmptyStore(@TempDir Path dir) throws Exception {
        Store store = new Store(dir.resolve("data.yml").toFile());
        store.load();
        assertFalse(store.has(NEW));
        assertEquals(0, store.countBy(FRIEND));
    }
}
