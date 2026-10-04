package dev.referrals;

/** Who may refer whom. Pure, so it can be tested without a server. */
final class Rules {

    enum Verdict { OK, SELF, ALREADY_REFERRED, NOT_NEW, REFERRER_TOO_NEW, LIMIT_REACHED, SAME_IP }

    private Rules() {
    }

    /**
     * @param self             the two players are the same person
     * @param playedTicks      how long the new player has played, in ticks
     * @param maxTicks         how new a player must be
     * @param alreadyReferred  the new player was referred before, by anyone
     * @param referrerTicks    how long the referrer has played, in ticks
     * @param minReferrerTicks how long a referrer must have played, 0 for no minimum
     * @param referralsSoFar   how many players the referrer has brought in
     * @param limit            the most one player may bring in, 0 for no limit
     * @param sameAddress      both players connect from the same address
     */
    static Verdict check(boolean self, long playedTicks, long maxTicks, boolean alreadyReferred, long referrerTicks,
                         long minReferrerTicks, int referralsSoFar, int limit, boolean sameAddress) {
        if (self) return Verdict.SELF;
        if (alreadyReferred) return Verdict.ALREADY_REFERRED;
        if (playedTicks > maxTicks) return Verdict.NOT_NEW;
        if (referrerTicks < minReferrerTicks) return Verdict.REFERRER_TOO_NEW;
        if (limit > 0 && referralsSoFar >= limit) return Verdict.LIMIT_REACHED;
        if (sameAddress) return Verdict.SAME_IP;
        return Verdict.OK;
    }
}
