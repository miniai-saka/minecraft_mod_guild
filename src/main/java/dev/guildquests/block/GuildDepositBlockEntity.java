package dev.guildquests.block;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.UUID;

public class GuildDepositBlockEntity extends BlockEntity implements SidedInventory {
    private static final int[] SLOTS = {0};

    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(1, ItemStack.EMPTY);
    private UUID ownerId;
    private String ownerName = "";

    private int tickCounter;
    private long lastNoticeAt = -100;
    private long unnotifiedCoins;

    public GuildDepositBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.GUILD_DEPOSIT_BE, pos, state);
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwner(UUID id, String name) {
        this.ownerId = id;
        this.ownerName = name == null ? "" : name;
        markDirty();
    }

    /**
     * Server ticker: periodically converts held coins into the owner's account balance.
     * Conversion is skipped while the owner is delinquent so coins wait in the block.
     * Throttled to every 5 ticks; owner notices are throttled to every 100 ticks (5s).
     */
    public static void tick(World world, BlockPos pos, BlockState state, GuildDepositBlockEntity be) {
        if (world.isClient) return;
        if (++be.tickCounter < 5) return;
        be.tickCounter = 0;

        if (be.ownerId == null) return;
        ItemStack stack = be.inventory.get(0);
        if (stack.isEmpty() || !stack.isOf(ModItems.COIN)) return;

        MinecraftServer server = world.getServer();
        if (server == null) return;
        GuildPersistentState guildState = GuildPersistentState.get(server);
        PlayerGuildData data = guildState.get(be.ownerId);
        if (data.delinquent) return;

        int count = stack.getCount();
        be.setStack(0, ItemStack.EMPTY);
        GuildService.creditCoins(server, be.ownerId, count);
        be.unnotifiedCoins += count;

        if (world.getTime() - be.lastNoticeAt >= 100 && be.unnotifiedCoins > 0) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(be.ownerId);
            if (owner != null) {
                GuildService.message(owner, be.unnotifiedCoins
                                + " コインが入金ブロックから自動で入金されました。残高: " + data.coins,
                        Formatting.GREEN);
                be.unnotifiedCoins = 0;
                be.lastNoticeAt = world.getTime();
            }
        }
    }

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    @Override
    public int size() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return inventory.get(0).isEmpty();
    }

    @Override
    public ItemStack getStack(int slot) {
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack stack = Inventories.splitStack(inventory, slot, amount);
        if (!stack.isEmpty()) markDirty();
        return stack;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack stack = Inventories.removeStack(inventory, slot);
        if (!stack.isEmpty()) markDirty();
        return stack;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (stack.getCount() > getMaxCountPerStack()) {
            stack.setCount(getMaxCountPerStack());
        }
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        inventory.clear();
    }

    @Override
    public boolean isValid(int slot, ItemStack stack) {
        return stack.isOf(ModItems.COIN);
    }

    // ------------------------------------------------------------------
    // SidedInventory (hoppers / transfer API)
    // ------------------------------------------------------------------

    @Override
    public int[] getAvailableSlots(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, Direction side) {
        if (!stack.isOf(ModItems.COIN)) return false;
        if (ownerId == null) return false;
        World world = getWorld();
        if (world == null || world.isClient) return true;
        MinecraftServer server = world.getServer();
        if (server == null) return true;
        return !GuildPersistentState.get(server).get(ownerId).delinquent;
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction side) {
        return false;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (ownerId != null) {
            nbt.putUuid("OwnerId", ownerId);
        }
        nbt.putString("OwnerName", ownerName);
        Inventories.writeNbt(nbt, inventory);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        ownerId = nbt.contains("OwnerId") ? nbt.getUuid("OwnerId") : null;
        ownerName = nbt.getString("OwnerName");
        inventory.clear();
        Inventories.readNbt(nbt, inventory);
    }
}
