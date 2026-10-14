package dev.guildquests.block;

import dev.guildquests.gui.DepositSettingsScreenHandler;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Containers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

public class GuildDepositBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);

    public GuildDepositBlock(Properties settings) {
        super(settings);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GuildDepositBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (world.isClientSide) return null;
        return createTickerHelper(type, ModBlocks.GUILD_DEPOSIT_BE, GuildDepositBlockEntity::tick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.setPlacedBy(world, pos, state, placer, itemStack);
        if (!world.isClientSide && placer instanceof ServerPlayer player
                && world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be) {
            be.setOwner(player.getUUID(), player.getGameProfile().getName());
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!(world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be)) {
            return InteractionResult.SUCCESS;
        }
        if (be.getOwnerId() == null) {
            be.setOwner(serverPlayer.getUUID(), serverPlayer.getGameProfile().getName());
            GuildService.message(serverPlayer, "この入金ブロックをあなたの口座に紐付けました。", ChatFormatting.GREEN);
            return InteractionResult.SUCCESS;
        }
        if (!be.getOwnerId().equals(serverPlayer.getUUID())) {
            GuildService.message(serverPlayer, "設置者のみ利用できます（設置者: " + be.getOwnerName() + "）。", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }
        DepositSettingsScreenHandler.open(serverPlayer, be);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && world.getBlockEntity(pos) instanceof GuildDepositBlockEntity be) {
            if (!world.isClientSide) {
                ItemStack pending = be.getItem(0);
                if (!pending.isEmpty() && pending.is(ModItems.COIN) && be.getOwnerId() != null) {
                    GuildService.creditCoins(world.getServer(), be.getOwnerId(), pending.getCount());
                } else if (!pending.isEmpty()) {
                    Containers.dropContents(world, pos, be);
                }
            }
        }
        super.onRemove(state, world, pos, newState, moved);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
