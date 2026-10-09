package dev.guildquests.net;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PublishedQuest;
import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import dev.guildquests.service.GuildService;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the button-GUI protocol.
 *
 * <p>C2S payloads on {@link NetworkIds#ACTION} are {@code "view|action|arg..."} strings; every
 * mutation is validated by {@link GuildService} and answered with a refreshed {@link GuiState}
 * on {@link NetworkIds#STATE}.
 */
public final class GuildNetwork {
    /** Bank fee context: true while the bank was opened through a guild bank block. */
    private static final Map<UUID, Boolean> BANK_FEE_WAIVED = new HashMap<>();

    private GuildNetwork() {
    }

    public static void initServer() {
        ServerPlayNetworking.registerGlobalReceiver(NetworkIds.ACTION,
                (server, player, handler, buf, responseSender) -> {
                    String payload = buf.readString();
                    server.execute(() -> handle(player, payload));
                });
    }

    /** Opens a view on the client (fresh state, openScreen=true). */
    public static void open(ServerPlayerEntity player, String view) {
        if ((view.equals("board") || view.equals("publish"))
                && !ConfigManager.config().playerQuestsEnabled) {
            GuildService.message(player, "公開クエスト機能は無効になっています。", Formatting.RED);
            return;
        }
        if (view.equals("bank")) BANK_FEE_WAIVED.put(player.getUuid(), false);
        push(player, view, true);
    }

    /** Opens the bank view, optionally with the fee waived (guild bank block interaction). */
    public static void openBank(ServerPlayerEntity player, boolean feeWaived) {
        BANK_FEE_WAIVED.put(player.getUuid(), feeWaived);
        push(player, "bank", true);
    }

    /** Refreshes an already-open view (openScreen=false). */
    public static void sendState(ServerPlayerEntity player, String view) {
        push(player, view, false);
    }

    private static void push(ServerPlayerEntity player, String view, boolean openScreen) {
        GuiState state = build(player, view);
        if (state == null) return;
        state.openScreen = openScreen;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(NetCodec.encode(state));
        ServerPlayNetworking.send(player, NetworkIds.STATE, buf);
    }

    private static GuiState build(ServerPlayerEntity player, String view) {
        return switch (view) {
            case "guild" -> GuiStateFactory.guild(player);
            case "board" -> GuiStateFactory.board(player);
            case "bank" -> GuiStateFactory.bank(player, feeWaived(player));
            case "transfer" -> GuiStateFactory.transfer(player);
            case "publish" -> GuiStateFactory.publish(player);
            default -> null;
        };
    }

    private static void handle(ServerPlayerEntity player, String payload) {
        if (player.isRemoved()) return;
        String[] parts = payload.split("\\|");
        if (parts.length < 2) return;
        switch (parts[0]) {
            case "guild" -> handleGuild(player, parts);
            case "board" -> handleBoard(player, parts);
            case "publish" -> handlePublish(player, parts);
            case "bank" -> handleBank(player, parts);
            case "transfer" -> handleTransfer(player, parts);
            default -> GuildQuestsMod.LOGGER.warn("Unknown GUI action view: {}", parts[0]);
        }
    }

    private static void handleGuild(ServerPlayerEntity player, String[] parts) {
        switch (parts[1]) {
            case "open" -> open(player, "guild");
            case "accept" -> {
                QuestDefinition quest = GuildService.resolveQuest(player.getServer(), arg(parts, 2));
                if (quest == null) {
                    GuildService.message(player, "そのクエストは存在しません。", Formatting.YELLOW);
                } else {
                    GuildService.acceptQuest(player, quest);
                }
                sendState(player, "guild");
            }
            case "submit" -> {
                GuildService.submitOrClaim(player, arg(parts, 2));
                sendState(player, "guild");
            }
            case "cancel" -> {
                GuildService.cancelQuest(player, arg(parts, 2));
                sendState(player, "guild");
            }
            default -> warn(parts);
        }
    }

    private static void handleBoard(ServerPlayerEntity player, String[] parts) {
        switch (parts[1]) {
            case "open" -> open(player, "board");
            case "accept" -> {
                GuildService.acceptPublishedQuest(player, arg(parts, 2));
                sendState(player, "board");
            }
            case "cancel" -> {
                String id = arg(parts, 2);
                PublishedQuest quest = GuildPersistentState.get(player.getServer()).findPublished(id);
                if (quest == null) {
                    GuildService.message(player, "その公開クエストは存在しません。", Formatting.YELLOW);
                } else if (player.getUuid().equals(quest.publisherId)) {
                    GuildService.cancelPublishedQuest(player, id);
                } else if (player.getUuid().equals(quest.takerId)) {
                    GuildService.cancelQuest(player, quest.questId());
                } else {
                    GuildService.message(player, "この依頼には関われません。", Formatting.RED);
                }
                sendState(player, "board");
            }
            default -> warn(parts);
        }
    }

    private static void handlePublish(ServerPlayerEntity player, String[] parts) {
        switch (parts[1]) {
            case "open" -> open(player, "publish");
            case "submit" -> {
                if (parts.length < 3) {
                    warn(parts);
                    return;
                }
                StringBuilder json = new StringBuilder();
                for (int i = 2; i < parts.length; i++) {
                    if (i > 2) json.append('|');
                    json.append(parts[i]);
                }
                try {
                    PublishRequest req = NetCodec.GSON.fromJson(json.toString(), PublishRequest.class);
                    if (req == null || req.target == null || req.target.isBlank()) {
                        GuildService.message(player, "対象が指定されていません。", Formatting.YELLOW);
                    } else {
                        QuestType type = "SUBMIT".equalsIgnoreCase(req.type) ? QuestType.SUBMIT : QuestType.KILL;
                        int amount = parseInt(req.amount, 1);
                        long reward = parseLong(req.reward, 0);
                        GuildService.publishQuest(player, type, req.target.trim(), amount, reward,
                                req.title, req.description);
                    }
                } catch (RuntimeException e) {
                    warn(parts);
                }
                sendState(player, "publish");
            }
            default -> warn(parts);
        }
    }

    private static void handleBank(ServerPlayerEntity player, String[] parts) {
        switch (parts[1]) {
            case "open" -> open(player, "bank");
            case "deposit" -> {
                GuildService.bankDeposit(player, parseLong(arg(parts, 2), 0), feeWaived(player));
                sendState(player, "bank");
            }
            case "withdraw" -> {
                GuildService.bankWithdraw(player, parseLong(arg(parts, 2), 0), feeWaived(player));
                sendState(player, "bank");
            }
            case "mode" -> {
                String mode = arg(parts, 2);
                if (GuildService.MODE_RIBO.equals(mode) || GuildService.MODE_DEBIT.equals(mode)
                        || GuildService.MODE_DISABLED.equals(mode)) {
                    GuildService.setPaymentMode(player, mode);
                }
                sendState(player, "bank");
            }
            case "transfer" -> open(player, "transfer");
            default -> warn(parts);
        }
    }

    private static void handleTransfer(ServerPlayerEntity player, String[] parts) {
        switch (parts[1]) {
            case "open" -> open(player, "transfer");
            case "send" -> {
                if (parts.length < 4) {
                    warn(parts);
                    return;
                }
                ServerPlayerEntity target = findOnline(player, arg(parts, 2));
                if (target == null) {
                    GuildService.message(player, "送金先がオンラインではありません。", Formatting.RED);
                } else {
                    GuildService.transferCoins(player, target, parseLong(parts[3], 0));
                }
                sendState(player, "transfer");
            }
            default -> warn(parts);
        }
    }

    /** Resolves a recipient at action time so a logged-off player is never used. */
    private static ServerPlayerEntity findOnline(ServerPlayerEntity sender, String uuidText) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uuid.equals(sender.getUuid())) return null;
        return sender.getServer().getPlayerManager().getPlayer(uuid);
    }

    private static boolean feeWaived(ServerPlayerEntity player) {
        return BANK_FEE_WAIVED.getOrDefault(player.getUuid(), false);
    }

    private static String arg(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void warn(String[] parts) {
        GuildQuestsMod.LOGGER.warn("Unknown GUI action: {}", String.join("|", parts));
    }
}
