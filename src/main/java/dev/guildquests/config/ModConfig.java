package dev.guildquests.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModConfig {
    /** Maximum number of active quests a player may have. */
    public int maxActiveQuests = 5;

    /** Check explore quests every N server ticks. 20 ticks = about 1 second. */
    public int exploreCheckIntervalTicks = 20;

    /** Minimum number of quests drawn for the daily board. Clamped to 1..9. */
    public int dailyQuestCountMin = 1;

    /** Maximum number of quests drawn for the daily board. Clamped to 1..9. */
    public int dailyQuestCountMax = 4;

    /** Default half-size (blocks) of the random coordinate box when a quest sets coordRange = 0. */
    public int exploreRandomRange = 3000;

    /** Emergency boss: allow the periodic spawn event. */
    public boolean bossEventEnabled = true;

    /** Real minutes between boss event rolls; counts only while a player is online. */
    public int bossEventIntervalMinutes = 30;

    /** Percent chance per roll that the boss actually spawns. Clamped to 0..100. */
    public int bossEventChancePercent = 20;

    /** Total coins split among players by damage share when the boss dies. */
    public long bossEventReward = 100;

    /** Max health of the emergency boss. */
    public double bossEventHealth = 300;

    /** Mix one urgent quest into the daily draw (red entry, boosted reward). */
    public boolean urgentQuestEnabled = true;

    /** Percent chance per in-game day that an urgent quest is drawn. 0..100. */
    public int urgentQuestChance = 60;

    /** Reward multiplier applied to urgent quests. Clamped to 1..100. */
    public double urgentRewardMultiplier = 2.0;

    /** Allow players to publish their own quests (bounty board) with an escrowed reward. */
    public boolean playerQuestsEnabled = true;

    /** In-game days before an untaken published quest expires (reward refunded). 0 disables. */
    public int publishedQuestExpireDays = 3;

    /** Maximum published quests per player (open + taken). */
    public int publishedQuestMaxPerPlayer = 5;

    /** Minimum reward for a published quest. */
    public long publishedQuestRewardMin = 1;

    /** Maximum reward for a published quest. */
    public long publishedQuestRewardMax = 1000;

    /** Allow paying Create shops (table cloth price tags set to guild coins) from the virtual balance. */
    public boolean createIntegrationEnabled = true;

    /** Bank transaction fee in percent, charged on deposit and withdraw. 0 disables fees. */
    public double bankFeePercent = 5.0;

    /** Ribo (revolving credit): fixed amount collected from the balance each interval. */
    public long riboInstallment = 500;

    /** Interest percent applied to outstanding ribo debt on every collection cycle. */
    public double riboInterestPercent = 2.0;

    /** Minutes between scheduled ribo collections. */
    public int riboIntervalMinutes = 60;

    /** Rank name -> required total completions. Insertion order is used for display. */
    public Map<String, Integer> rankThresholds = new LinkedHashMap<>();

    public ModConfig() {
        rankThresholds.put("E", 0);
        rankThresholds.put("D", 5);
        rankThresholds.put("C", 15);
        rankThresholds.put("B", 30);
        rankThresholds.put("A", 50);
        rankThresholds.put("S", 80);
    }
}
