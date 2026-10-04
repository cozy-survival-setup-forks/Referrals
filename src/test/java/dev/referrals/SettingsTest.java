package dev.referrals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsTest {

    @Test
    void aliasesAreCleanedUp() {
        assertEquals(List.of("referral", "invite", "my-ref_2"),
                Settings.aliases(List.of("Referral", " /invite ", "referral", "ref", "my-ref_2", "bad name", "x;y", "", "/")));
    }

    @Test
    void noAliasesByDefault() {
        assertEquals(List.of(), Settings.aliases(List.of()));
    }
}
