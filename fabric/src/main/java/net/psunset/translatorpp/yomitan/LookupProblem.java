package net.psunset.translatorpp.yomitan;

/** A lookup can't be answered for a reason the player can fix; the message is shown in the popup. */
public final class LookupProblem extends RuntimeException {
    public LookupProblem(String message) {
        super(message);
    }
}
