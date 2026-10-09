package dev.guildquests.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GuildPersistentState extends PersistentState {
    private static final String SAVE_ID = "guildquests_players";
    private final Map<UUID, PlayerGuildData> players = new HashMap<>();

    /** In-game day number the current daily board was drawn for. -1 = never drawn. */
    public long dailyBoardDay = -1;
    /** Today's server-wide drawn quests. Empty until the first draw. */
    public final List<DailyQuest> dailyBoard = new ArrayList<>();
    /** Player-published quests (bounty board). */
    public final List<PublishedQuest> publishedQuests = new ArrayList<>();

    public PlayerGuildData get(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new PlayerGuildData());
    }

    /** Live view used for scheduled jobs such as ribo collection. */
    public Map<UUID, PlayerGuildData> players() {
        return players;
    }

    /** Today's drawn quest by quest-definition id, or null when not on the board. */
    public DailyQuest findDaily(String questId) {
        for (DailyQuest quest : dailyBoard) {
            if (quest.questId.equals(questId)) {
                return quest;
            }
        }
        return null;
    }

    /** Published quest by its raw id (without the "pub:" prefix), or null. */
    public PublishedQuest findPublished(String id) {
        for (PublishedQuest quest : publishedQuests) {
            if (quest.id.equals(id)) {
                return quest;
            }
        }
        return null;
    }

    /** Published quest by the synthesized quest id ("pub:<id>"), or null. */
    public PublishedQuest findByQuestId(String questId) {
        for (PublishedQuest quest : publishedQuests) {
            if (quest.isQuestId(questId)) {
                return quest;
            }
        }
        return null;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound playersNbt = new NbtCompound();
        for (Map.Entry<UUID, PlayerGuildData> entry : players.entrySet()) {
            playersNbt.put(entry.getKey().toString(), entry.getValue().toNbt());
        }
        nbt.put("Players", playersNbt);
        nbt.putLong("DailyBoardDay", dailyBoardDay);
        NbtList dailyNbt = new NbtList();
        for (DailyQuest quest : dailyBoard) {
            dailyNbt.add(quest.toNbt());
        }
        nbt.put("DailyQuests", dailyNbt);
        NbtList publishedNbt = new NbtList();
        for (PublishedQuest quest : publishedQuests) {
            publishedNbt.add(quest.toNbt());
        }
        nbt.put("PublishedQuests", publishedNbt);
        return nbt;
    }

    public static GuildPersistentState fromNbt(NbtCompound nbt) {
        GuildPersistentState state = new GuildPersistentState();
        NbtCompound playersNbt = nbt.getCompound("Players");
        for (String key : playersNbt.getKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                state.players.put(uuid, PlayerGuildData.fromNbt(playersNbt.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID keys rather than preventing the world from loading.
            }
        }
        state.dailyBoardDay = nbt.getLong("DailyBoardDay");
        NbtList dailyNbt = nbt.getList("DailyQuests", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < dailyNbt.size(); i++) {
            state.dailyBoard.add(DailyQuest.fromNbt(dailyNbt.getCompound(i)));
        }
        NbtList publishedNbt = nbt.getList("PublishedQuests", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < publishedNbt.size(); i++) {
            state.publishedQuests.add(PublishedQuest.fromNbt(publishedNbt.getCompound(i)));
        }
        return state;
    }

    public static GuildPersistentState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                GuildPersistentState::fromNbt,
                GuildPersistentState::new,
                SAVE_ID
        );
    }
}
