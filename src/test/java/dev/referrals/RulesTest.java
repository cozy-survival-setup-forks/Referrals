package dev.referrals;

import dev.referrals.Rules.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RulesTest {

    private static final long FIVE_MINUTES = 5 * 60 * 20;

    @Test
    void aNewPlayerCanBeReferred() {
        assertEquals(Verdict.OK, Rules.check(false, 100, FIVE_MINUTES, false, 0, 10, false));
        assertEquals(Verdict.OK, Rules.check(false, FIVE_MINUTES, FIVE_MINUTES, false, 0, 10, false));
    }

    @Test
    void youCannotReferYourselfOrAnOldPlayerOrSomeoneTwice() {
        assertEquals(Verdict.SELF, Rules.check(true, 0, FIVE_MINUTES, false, 0, 10, false));
        assertEquals(Verdict.NOT_NEW, Rules.check(false, FIVE_MINUTES + 1, FIVE_MINUTES, false, 0, 10, false));
        assertEquals(Verdict.ALREADY_REFERRED, Rules.check(false, 0, FIVE_MINUTES, true, 0, 10, false));
    }

    @Test
    void theLimitAndTheSameAddressBlock() {
        assertEquals(Verdict.LIMIT_REACHED, Rules.check(false, 0, FIVE_MINUTES, false, 10, 10, false));
        assertEquals(Verdict.OK, Rules.check(false, 0, FIVE_MINUTES, false, 1000, 0, false));
        assertEquals(Verdict.SAME_IP, Rules.check(false, 0, FIVE_MINUTES, false, 0, 10, true));
    }

    @Test
    void anAlreadyReferredPlayerIsReportedAsSuchEvenWhenOld() {
        assertEquals(Verdict.ALREADY_REFERRED, Rules.check(false, FIVE_MINUTES * 10, FIVE_MINUTES, true, 0, 10, false));
    }
}
