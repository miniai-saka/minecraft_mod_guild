package dev.guildquests.gui;

import dev.guildquests.service.GuildService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HorseRaceScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int FINISH = 100;
    private static final String[] HORSE_NAMES = {"白馬", "黒馬", "栗毛", "鹿毛"};
    private static final int[] ODDS = {3, 5, 2, 8};
    private static final Item[] HORSE_ICONS = {Items.WHITE_WOOL, Items.BLACK_WOOL, Items.BROWN_WOOL, Items.ORANGE_WOOL};

    private static final Map<UUID, HorseRaceScreenHandler> ACTIVE_RACES = new ConcurrentHashMap<>();

    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private long betAmount;
    private int selectedHorse = -1;
    private boolean raceActive = false;
    private boolean raceDone = false;
    private int winner = -1;
    private final int[] positions = {0, 0, 0, 0};
    private int raceTicks = 0;

    public HorseRaceScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.owner = owner;

        // 54 display slots (9x6)
        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            addSlot(new Slot(display, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override public boolean canInsert(ItemStack stack) { return false; }
                @Override public boolean canTakeItems(PlayerEntity player) { return false; }
            });
        }
        // Player inventory: 27 main slots + 9 hotbar (matching vanilla 9x6 layout)
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));

        populate();
    }

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new HorseRaceScreenHandler(syncId, playerInventory, player),
                Text.literal("競馬")
        ));
    }

    public static void tickAll(MinecraftServer server) {
        if (ACTIVE_RACES.isEmpty()) return;
        ACTIVE_RACES.entrySet().removeIf(entry -> {
            HorseRaceScreenHandler h = entry.getValue();
            if (!h.raceActive) return true;

            java.util.Random rand = new java.util.Random();
            for (int i = 0; i < 4; i++) {
                if (h.positions[i] < FINISH) {
                    h.positions[i] += 2 + rand.nextInt(4);
                    if (h.positions[i] >= FINISH) {
                        h.positions[i] = FINISH;
                        if (h.winner == -1) h.winner = i;
                    }
                }
            }
            h.raceTicks++;

            boolean allDone = true;
            for (int pos : h.positions) {
                if (pos < FINISH) { allDone = false; break; }
            }

            if (allDone || h.raceTicks > 100) {
                h.raceActive = false;
                h.raceDone = true;
                h.finishRace();
                // Show result screen before removing from map
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
                if (p != null && p.currentScreenHandler == h) {
                    h.populate();
                    h.sendContentUpdates();
                }
                return true;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null && player.currentScreenHandler == h) {
                h.populate();
                h.sendContentUpdates();
            }
            return false;
        });
    }

    private void finishRace() {
        if (winner == selectedHorse && selectedHorse >= 0) {
            long payout = betAmount * ODDS[selectedHorse];
            GuildService.creditCoins(owner.getServer(), owner.getUuid(), payout);
        }
    }

    private void startRace() {
        if (betAmount <= 0 || selectedHorse < 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;
        raceActive = true;
        raceDone = false;
        winner = -1;
        positions[0] = 0; positions[1] = 0; positions[2] = 0; positions[3] = 0;
        raceTicks = 0;
        ACTIVE_RACES.put(owner.getUuid(), this);
    }

    private void nextRace() {
        raceActive = false;
        raceDone = false;
        winner = -1;
        selectedHorse = -1;
        betAmount = 0;
        positions[0] = 0; positions[1] = 0; positions[2] = 0; positions[3] = 0;
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, ItemStack.EMPTY);

        long balance = GuildService.data(owner).coins;

        if (!raceActive && !raceDone) {
            // === SELECTION MODE ===
            // Row 0 (slots 0-8): horse selection
            for (int i = 0; i < 4; i++) {
                final int horseIdx = i;
                boolean selected = i == selectedHorse;
                String label = HORSE_NAMES[i] + " (" + ODDS[i] + "倍)";
                if (selected) {
                    setAction(i, item(HORSE_ICONS[i], "● " + label, "選択中"),
                            (p, b, t) -> { if (b == 0) selectedHorse = horseIdx; });
                } else {
                    setAction(i, item(HORSE_ICONS[i], label, "クリックで選択"),
                            (p, b, t) -> { if (b == 0) selectedHorse = horseIdx; });
                }
            }

            // Row 1 (slots 9-17): empty during selection
            // Row 2 (slots 18-26): empty during selection
            // Row 3 (slots 27-35): empty during selection

            // Row 4 (slots 36-44): betting
            setAction(36, item(Items.PAPER, "賭け +10", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 10); });
            setAction(37, item(Items.PAPER, "賭け +50", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 50); });
            setAction(38, item(Items.PAPER, "賭け +100", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 100); });
            setAction(39, item(Items.PAPER, "賭け +500", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 500); });
            set(40, item(Items.GOLD_INGOT, "賭け: " + betAmount));

            // Row 5 (slots 45-53): start button
            if (betAmount > 0 && selectedHorse >= 0 && betAmount <= balance) {
                setAction(49, item(Items.LIME_CONCRETE, "レース開始",
                        HORSE_NAMES[selectedHorse] + " に " + betAmount + " コイン"),
                        (p, b, t) -> { if (b == 0) startRace(); });
            } else {
                String reason = selectedHorse < 0 ? "馬を選択してください"
                        : betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足";
                set(49, item(Items.GRAY_CONCRETE, "レース開始", reason));
            }
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeHandledScreen());

        } else if (raceActive) {
            // === RACE MODE ===
            // Rows 0-3 (slots 0-35): race lanes, one horse per row
            for (int lane = 0; lane < 4; lane++) {
                // Each lane has 9 slots (0-8 within the row)
                // Position 0-100 maps to slot 0-8
                int slotInLane = Math.min(8, positions[lane] * 9 / 100);
                int baseSlot = lane * 9;

                // Fill lane with track
                for (int s = 0; s < 9; s++) {
                    if (s == slotInLane) {
                        // The horse is here
                        set(baseSlot + s, item(HORSE_ICONS[lane], HORSE_NAMES[lane], positions[lane] + "%"));
                    } else if (s == 8) {
                        // Finish line
                        set(baseSlot + s, item(Items.WHITE_BANNER, "Goal", " "));
                    } else {
                        // Empty track
                        set(baseSlot + s, item(Items.LIGHT_GRAY_STAINED_GLASS_PANE, " ", " "));
                    }
                }
            }

            // Row 4 (slots 36-44): race info
            set(40, item(Items.CLOCK, "レース中…", raceTicks / 20 + " 秒"));
            if (selectedHorse >= 0) {
                set(36, item(HORSE_ICONS[selectedHorse], "応援中: " + HORSE_NAMES[selectedHorse], ODDS[selectedHorse] + "倍"));
            }

            // Row 5 (slots 45-53): close
            set(49, item(Items.GRAY_CONCRETE, "レース中…", "しばらくお待ちください"));
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeHandledScreen());

        } else {
            // === RESULT MODE ===
            // Rows 0-3: show final positions
            for (int lane = 0; lane < 4; lane++) {
                int slotInLane = Math.min(8, positions[lane] * 9 / 100);
                int baseSlot = lane * 9;
                boolean isWinner = lane == winner;

                for (int s = 0; s < 9; s++) {
                    if (s == slotInLane) {
                        String label = isWinner ? "★ " + HORSE_NAMES[lane] : HORSE_NAMES[lane];
                        String lore = isWinner ? "1st!" : positions[lane] + "%";
                        set(baseSlot + s, item(isWinner ? Items.GOLD_BLOCK : HORSE_ICONS[lane], label, lore));
                    } else if (s == 8) {
                        set(baseSlot + s, item(Items.WHITE_BANNER, "Goal", " "));
                    } else {
                        set(baseSlot + s, item(Items.LIGHT_GRAY_STAINED_GLASS_PANE, " ", " "));
                    }
                }
            }

            // Row 4: result info
            if (winner >= 0) {
                boolean won = selectedHorse == winner;
                long payout = won ? betAmount * ODDS[selectedHorse] : 0;
                set(40, item(won ? Items.LIME_CONCRETE : Items.RED_CONCRETE,
                        won ? "勝ち！ +" + payout : "負け… -" + betAmount,
                        HORSE_NAMES[winner] + " が勝ち"));
            }

            // Row 5: next race
            setAction(49, item(Items.LIME_CONCRETE, "もう一回", "新しいレースを開始"),
                    (p, b, t) -> { if (b == 0) nextRace(); });
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeHandledScreen());
        }
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }



    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS && player instanceof ServerPlayerEntity serverPlayer) {
            MenuAction action = actions.get(slotIndex);
            if (action != null) {
                action.run(serverPlayer, button, actionType);
                if (serverPlayer.currentScreenHandler == this) {
                    populate();
                    sendContentUpdates();
                }
            }
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean canUse(PlayerEntity player) { return player == owner && !owner.isRemoved(); }
}
