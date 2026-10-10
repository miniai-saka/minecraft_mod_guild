package dev.guildquests.block;

import dev.guildquests.gui.DepositSettingsScreenHandler;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class GuildDepositBlock extends BlockWithEntity {
    private static final VoxelShape SHAPE = Block.createCuboidShape(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);

    public GuildDepositBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GuildDepositBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient) return null;
        return checkType(type, ModBlocks.GUILD_DEPOSIT_BE, GuildDepositBlockEntity::tick);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient && placer instanceof ServerPlayerEntity player
                && world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be) {
            be.setOwner(player.getUuid(), player.getGameProfile().getName());
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.SUCCESS;
        }
        if (!(world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be)) {
            return ActionResult.SUCCESS;
        }
        if (be.getOwnerId() == null) {
            be.setOwner(serverPlayer.getUuid(), serverPlayer.getGameProfile().getName());
            GuildService.message(serverPlayer, "この入金ブロックをあなたの口座に紐付けました。", Formatting.GREEN);
            return ActionResult.SUCCESS;
        }
        if (!be.getOwnerId().equals(serverPlayer.getUuid())) {
            GuildService.message(serverPlayer, "設置者のみ利用できます（設置者: " + be.getOwnerName() + "）。", Formatting.YELLOW);
            return ActionResult.SUCCESS;
        }
        DepositSettingsScreenHandler.open(serverPlayer, be);
        return ActionResult.SUCCESS;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be) {
            if (!world.isClient) {
                ItemStack pending = be.getStack(0);
                if (!pending.isEmpty() && pending.isOf(ModItems.COIN) && be.getOwnerId() != null) {
                    GuildService.creditCoins(world.getServer(), be.getOwnerId(), pending.getCount());
                } else if (!pending.isEmpty()) {
                    ItemScatterer.spawn(world, pos, be);
                }
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }
}
