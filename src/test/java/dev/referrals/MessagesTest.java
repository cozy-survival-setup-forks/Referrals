package dev.referrals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                MessagesTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
    }

    private static void collect(Component component, List<ClickEvent> into) {
        if (component.clickEvent() != null) into.add(component.clickEvent());
        component.children().forEach(child -> collect(child, into));
    }

    @Test
    void theRequestMessageHasAcceptAndDenyButtonsForTheSender() {
        String line = bundled().getStringList("request-received").get(1);
        Component parsed = new Messages(null).parse(line, "player", "Steve", "seconds", "120");

        List<ClickEvent> clicks = new ArrayList<>();
        collect(parsed, clicks);
        assertEquals(2, clicks.size());
        assertEquals(ClickEvent.Action.RUN_COMMAND, clicks.get(0).action());
        assertEquals("/ref accept Steve", ((ClickEvent.Payload.Text) clicks.get(0).payload()).value());
        assertEquals("/ref deny Steve", ((ClickEvent.Payload.Text) clicks.get(1).payload()).value());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(parsed).contains("120 seconds"));
    }

    @Test
    void everyRefusalTheCodeCanSendHasAText() {
        YamlConfiguration messages = bundled();
        for (Rules.Verdict verdict : Rules.Verdict.values()) {
            if (verdict == Rules.Verdict.OK) continue;
            String key = "refused-" + verdict.name().toLowerCase().replace('_', '-');
            assertTrue(messages.isString(key), key + " is missing from messages.yml");
        }
        for (Requests.Added added : Requests.Added.values()) {
            if (added == Requests.Added.ADDED) continue;
            String key = "request-" + added.name().toLowerCase().replace('_', '-');
            assertTrue(messages.isString(key), key + " is missing from messages.yml");
        }
    }
}
