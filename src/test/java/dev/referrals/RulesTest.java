package dev.referrals;

import dev.referrals.Rules.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RulesTest {

    private static final long FIVE_MINUTES = 5 * 60 * 20;
    private static final long HALF_HOUR = 30 * 60 * 20;

    private static Verdict check(boolean self, long played, boolean already, long referrerPlayed, int sofar, int limit, boolean sameAddress) {
        return Rules.check(self, played, FIVE_MINUTES, already, referrerPlayed, HALF_HOUR, sofar, limit, sameAddress);
    }

    @Test
    void aNewPlayerCanBeReferredByAnExperiencedOne() {
        assertEquals(Verdict.OK, check(false, 100, false, HALF_HOUR * 4, 0, 10, false));
        assertEquals(Verdict.OK, check(false, FIVE_MINUTES, false, HALF_HOUR, 0, 10, false));
    }

    @Test
    void youCannotReferYourselfOrAnOldPlayerOrSomeoneTwice() {
        assertEquals(Verdict.SELF, check(true, 0, false, HALF_HOUR, 0, 10, false));
        assertEquals(Verdict.NOT_NEW, check(false, FIVE_MINUTES + 1, false, HALF_HOUR, 0, 10, false));
        assertEquals(Verdict.ALREADY_REFERRED, check(false, 0, true, HALF_HOUR, 0, 10, false));
        assertEquals(Verdict.ALREADY_REFERRED, check(false, FIVE_MINUTES * 10, true, HALF_HOUR, 0, 10, false));
    }

    @Test
    void aBrandNewAccountCannotRefer() {
        assertEquals(Verdict.REFERRER_TOO_NEW, check(false, 0, false, HALF_HOUR - 1, 0, 10, false));
        assertEquals(Verdict.OK, Rules.check(false, 0, FIVE_MINUTES, false, 0, 0, 0, 10, false), "a minimum of 0 turns it off");
    }

    @Test
    void theLimitAndTheSameAddressBlock() {
        assertEquals(Verdict.LIMIT_REACHED, check(false, 0, false, HALF_HOUR, 10, 10, false));
        assertEquals(Verdict.OK, check(false, 0, false, HALF_HOUR, 1000, 0, false));
        assertEquals(Verdict.SAME_IP, check(false, 0, false, HALF_HOUR, 0, 10, true));
    }
}
