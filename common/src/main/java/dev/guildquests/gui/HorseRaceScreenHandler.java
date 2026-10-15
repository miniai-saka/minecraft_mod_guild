package dev.guildquests.gui;

import dev.guildquests.service.GuildService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HorseRaceScreenHandler extends AbstractContainerMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int FINISH = 100;
    private static final String[] HORSE_NAMES = {"白馬", "黒馬", "栗毛", "鹿毛"};
    private static final int[] ODDS = {3, 5, 2, 8};
    private static final Item[] HORSE_ICONS = {Items.WHITE_WOOL, Items.BLACK_WOOL, Items.BROWN_WOOL, Items.ORANGE_WOOL};

    /** Per-player race state; survives GUI reopen so an in-flight race is never orphaned. */
    private static final class RaceState {
        long betAmount;
        int selectedHorse = -1;
        boolean raceActive;
        boolean raceDone;
        boolean menuOpen;
        int winner = -1;
        final int[] positions = {0, 0, 0, 0};
        int raceTicks;
    }

    private static final Map<UUID, RaceState> RACES = new ConcurrentHashMap<>();

    /** Clears all race state; called on server stop to avoid cross-world leaks. */
    public static void clearAll() {
        RACES.clear();
    }

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private final RaceState state;

    public HorseRaceScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(MenuType.GENERIC_9x6, syncId);
        this.owner = owner;
        this.state = RACES.computeIfAbsent(owner.getUUID(), ignored -> new RaceState());
        this.state.menuOpen = true;

        // 54 display slots (9x6)
        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            addSlot(new Slot(display, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
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

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new HorseRaceScreenHandler(syncId, playerInventory, player),
                Component.literal("競馬")
        ));
    }

    public static void tickAll(MinecraftServer server) {
        if (RACES.isEmpty()) return;
        RACES.entrySet().removeIf(entry -> {
            RaceState s = entry.getValue();
            if (!s.raceActive) return false;

            java.util.Random rand = new java.util.Random();
            for (int i = 0; i < 4; i++) {
                if (s.positions[i] < FINISH) {
                    s.positions[i] += 2 + rand.nextInt(4);
                    if (s.positions[i] >= FINISH) {
                        s.positions[i] = FINISH;
                        if (s.winner == -1) s.winner = i;
                    }
                }
            }
            s.raceTicks++;

            boolean allDone = true;
            for (int pos : s.positions) {
                if (pos < FINISH) { allDone = false; break; }
            }

            if (allDone || s.raceTicks > 100) {
                s.raceActive = false;
                s.raceDone = true;
                finishRace(server, entry.getKey(), s);
                ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                if (p != null && p.containerMenu instanceof HorseRaceScreenHandler h && h.state == s) {
                    h.populate();
                    h.broadcastChanges();
                }
                return false;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.containerMenu instanceof HorseRaceScreenHandler h && h.state == s) {
                h.populate();
                h.broadcastChanges();
            }
            return false;
        });
        // Keep a state only while its menu is open or a race is in progress; drop the rest
        // so the map cannot grow. Crucially, do NOT drop a state mid-selection (menu open).
        RACES.entrySet().removeIf(entry -> {
            RaceState s = entry.getValue();
            return !s.raceActive && !s.menuOpen;
        });
    }

    private static void finishRace(MinecraftServer server, UUID playerId, RaceState s) {
        if (s.winner == s.selectedHorse && s.selectedHorse >= 0) {
            long payout = s.betAmount * ODDS[s.selectedHorse];
            GuildService.creditCoins(server, playerId, payout);
        }
    }

    private void startRace() {
        if (state.raceActive) return;
        if (state.betAmount <= 0 || state.selectedHorse < 0) return;
        if (!GuildService.payCoins(owner, state.betAmount)) return;
        // Ensure the tick loop can see this race even if the entry was evicted while idle.
        RACES.put(owner.getUUID(), state);
        state.raceActive = true;
        state.raceDone = false;
        state.winner = -1;
        state.positions[0] = 0; state.positions[1] = 0; state.positions[2] = 0; state.positions[3] = 0;
        state.raceTicks = 0;
    }

    private void nextRace() {
        state.raceActive = false;
        state.raceDone = false;
        state.winner = -1;
        state.selectedHorse = -1;
        state.betAmount = 0;
        state.positions[0] = 0; state.positions[1] = 0; state.positions[2] = 0; state.positions[3] = 0;
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);

        long balance = GuildService.data(owner).coins;

        if (!state.raceActive && !state.raceDone) {
            // === SELECTION MODE ===
            // Row 0 (slots 0-8): horse selection
            for (int i = 0; i < 4; i++) {
                final int horseIdx = i;
                boolean selected = i == state.selectedHorse;
                String label = HORSE_NAMES[i] + " (" + ODDS[i] + "倍)";
                if (selected) {
                    setAction(i, item(HORSE_ICONS[i], "● " + label, "選択中"),
                            (p, b, t) -> { if (b == 0) state.selectedHorse = horseIdx; });
                } else {
                    setAction(i, item(HORSE_ICONS[i], label, "クリックで選択"),
                            (p, b, t) -> { if (b == 0) state.selectedHorse = horseIdx; });
                }
            }

            // Row 4 (slots 36-44): betting
            setAction(36, item(Items.PAPER, "賭け +10", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) state.betAmount = Math.min(balance, state.betAmount + 10); });
            setAction(37, item(Items.PAPER, "賭け +50", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) state.betAmount = Math.min(balance, state.betAmount + 50); });
            setAction(38, item(Items.PAPER, "賭け +100", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) state.betAmount = Math.min(balance, state.betAmount + 100); });
            setAction(39, item(Items.PAPER, "賭け +500", "所持: " + balance),
                    (p, b, t) -> { if (b == 0) state.betAmount = Math.min(balance, state.betAmount + 500); });
            set(40, item(Items.GOLD_INGOT, "賭け: " + state.betAmount));

            // Row 5 (slots 45-53): start button
            if (state.betAmount > 0 && state.selectedHorse >= 0 && state.betAmount <= balance) {
                setAction(49, item(Items.LIME_CONCRETE, "レース開始",
                        HORSE_NAMES[state.selectedHorse] + " に " + state.betAmount + " コイン"),
                        (p, b, t) -> { if (b == 0) startRace(); });
            } else {
                String reason = state.selectedHorse < 0 ? "馬を選択してください"
                        : state.betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足";
                set(49, item(Items.GRAY_CONCRETE, "レース開始", reason));
            }
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeContainer());

        } else if (state.raceActive) {
            // === RACE MODE ===
            for (int lane = 0; lane < 4; lane++) {
                int slotInLane = Math.min(8, state.positions[lane] * 9 / 100);
                int baseSlot = lane * 9;
                for (int s = 0; s < 9; s++) {
                    if (s == slotInLane) {
                        set(baseSlot + s, item(HORSE_ICONS[lane], HORSE_NAMES[lane], state.positions[lane] + "%"));
                    } else if (s == 8) {
                        set(baseSlot + s, item(Items.WHITE_BANNER, "Goal", " "));
                    } else {
                        set(baseSlot + s, item(Items.LIGHT_GRAY_STAINED_GLASS_PANE, " ", " "));
                    }
                }
            }

            set(40, item(Items.CLOCK, "レース中…", state.raceTicks / 20 + " 秒"));
            if (state.selectedHorse >= 0) {
                set(36, item(HORSE_ICONS[state.selectedHorse], "応援中: " + HORSE_NAMES[state.selectedHorse], ODDS[state.selectedHorse] + "倍"));
            }

            set(49, item(Items.GRAY_CONCRETE, "レース中…", "しばらくお待ちください"));
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeContainer());

        } else {
            // === RESULT MODE ===
            for (int lane = 0; lane < 4; lane++) {
                int slotInLane = Math.min(8, state.positions[lane] * 9 / 100);
                int baseSlot = lane * 9;
                boolean isWinner = lane == state.winner;

                for (int s = 0; s < 9; s++) {
                    if (s == slotInLane) {
                        String label = isWinner ? "★ " + HORSE_NAMES[lane] : HORSE_NAMES[lane];
                        String lore = isWinner ? "1st!" : state.positions[lane] + "%";
                        set(baseSlot + s, item(isWinner ? Items.GOLD_BLOCK : HORSE_ICONS[lane], label, lore));
                    } else if (s == 8) {
                        set(baseSlot + s, item(Items.WHITE_BANNER, "Goal", " "));
                    } else {
                        set(baseSlot + s, item(Items.LIGHT_GRAY_STAINED_GLASS_PANE, " ", " "));
                    }
                }
            }

            if (state.winner >= 0) {
                boolean won = state.selectedHorse == state.winner;
                long payout = won ? state.betAmount * ODDS[state.selectedHorse] : 0;
                set(40, item(won ? Items.LIME_CONCRETE : Items.RED_CONCRETE,
                        won ? "勝ち！ +" + payout : "負け… -" + state.betAmount,
                        HORSE_NAMES[state.winner] + " が勝ち"));
            }

            setAction(49, item(Items.LIME_CONCRETE, "もう一回", "新しいレースを開始"),
                    (p, b, t) -> { if (b == 0) nextRace(); });
            setAction(53, item(Items.BARRIER, "閉じる"),
                    (p, b, t) -> p.closeContainer());
        }
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }

    private void set(int slot, ItemStack stack) {
        display.setItem(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS && player instanceof ServerPlayer serverPlayer
                && actionType == ClickType.PICKUP) {
            MenuAction action = actions.get(slotIndex);
            if (action != null) {
                action.run(serverPlayer, button, actionType);
                if (serverPlayer.containerMenu == this) {
                    populate();
                    broadcastChanges();
                }
            }
            return;
        }
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) return;
        super.clicked(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) { return player == owner && !owner.isRemoved(); }

    @Override
    public void removed(Player player) {
        super.removed(player);
        state.menuOpen = false;
    }
}
