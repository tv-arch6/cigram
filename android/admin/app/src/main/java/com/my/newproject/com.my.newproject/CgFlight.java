package com.my.newproject;

import java.util.ArrayList;
import java.util.List;

/**
 * One request set at a time (18.3) — pure Java, unit-tested. The admin screens used to call CgData.load() each on its own, so the
 * dashboard timer, the «تحديث» button and every screen that opened could download the 5 MB catalogue concurrently; and an answer
 * requested before an edit could overwrite the edited snapshot.
 *
 * {@link #joinOrStart}: the first caller starts the work, later callers are queued on the same flight and answered by the same
 * result. {@link #finish} hands back everybody who waited. {@link #staleAfterEdit} and {@link #isFresh} are the two small
 * decisions CgData needs: a response that started before a local edit must not replace it, and a snapshot younger than a limit
 * needs no request at all.
 */
final class CgFlight<W> {

    private List<W> waiters;

    /** @return true when the caller must start the work (nobody was loading); false when it joined a running flight. */
    synchronized boolean joinOrStart(W waiter) {
        if (waiters != null) {
            waiters.add(waiter);
            return false;
        }
        waiters = new ArrayList<W>();
        waiters.add(waiter);
        return true;
    }

    /** Ends the flight and returns everyone who waited for it (never null; empty when none was running). */
    synchronized List<W> finish() {
        List<W> out = waiters == null ? new ArrayList<W>() : waiters;
        waiters = null;
        return out;
    }

    synchronized boolean running() {
        return waiters != null;
    }

    /** An answer fetched while an edit was made locally may predate that edit: keep the edited snapshot instead. */
    static boolean staleAfterEdit(int editsAtStart, int editsNow, boolean haveCurrent, boolean answerFromCache) {
        return editsNow != editsAtStart && haveCurrent && !answerFromCache;
    }

    /** True when a snapshot loaded at {@code loadedAt} from the server (not the device cache) is younger than {@code maxAgeMs}. */
    static boolean isFresh(boolean haveSnapshot, boolean fromCache, long loadedAt, long now, long maxAgeMs) {
        long age = now - loadedAt;
        return haveSnapshot && !fromCache && age >= 0L && age < maxAgeMs;
    }

    /** Delay of the next background refresh: base, doubled per consecutive failure (at most 3 doublings), capped. */
    static long refreshDelayMs(long baseMs, int failures, long capMs) {
        int f = Math.max(0, Math.min(failures, 3));
        return Math.min(capMs, baseMs << f);
    }
}
