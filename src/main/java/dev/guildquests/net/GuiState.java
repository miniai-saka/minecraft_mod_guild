package dev.guildquests.net;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON-serialized view state pushed from the server to the client screens.
 * Only the fields relevant to {@link #view} are populated; the rest stay at defaults.
 */
public final class GuiState {
    /** One of: guild, bank, transfer. */
    public String view = "";
    /** true: the client should (re)open the screen; false: update it only when already open. */
    public boolean openScreen = false;

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------
    /** Electronic bank balance (data.coins). */
    public long balance;
    /** Physical coin items held in the inventory. */
    public long held;
    public String rank = "";
    public int completedTotal;
    public int maxActive;
    public int dailyCount;
    public String nextDraw = "";

    // ------------------------------------------------------------------
    // guild view
    // ------------------------------------------------------------------
    public List<QuestRow> daily = new ArrayList<>();
    public List<QuestRow> active = new ArrayList<>();

    // ------------------------------------------------------------------
    // bank view
    // ------------------------------------------------------------------
    public boolean feeWaived;
    /** Bank fee percentage (config.bankFeePercent). */
    public double feeRate;
    public String paymentMode = "";
    public long debt;
    public boolean delinquent;
    public long nextRiboMinutes;
    public long riboInstallment;
    public double riboInterestPercent;
    public int riboIntervalMinutes;

    // ------------------------------------------------------------------
    // transfer view
    // ------------------------------------------------------------------
    public List<Candidate> candidates = new ArrayList<>();

    public static final class QuestRow {
        public String questId = "";
        public String title = "";
        public String description = "";
        public String objective = "";
        public long reward;
        /** AVAILABLE / ACTIVE / LOCKED / DONE_ONCE / BROKEN */
        public String status = "";
        public int progress;
        public int amount;
        /** Active quest whose progress is complete: claimable (or ready to deliver). */
        public boolean ready;
        /** Active SUBMIT quest that still needs items delivered. */
        public boolean canSubmit;
        public String lockReason = "";
    }

    public static final class Candidate {
        public String uuid = "";
        public String name = "";
        public long balance;
    }
}
