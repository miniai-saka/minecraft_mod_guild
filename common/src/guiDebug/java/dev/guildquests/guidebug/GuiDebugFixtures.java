package dev.guildquests.guidebug;

import dev.guildquests.block.*;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.*;
import dev.guildquests.gui.*;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.UUID;

/** Fixtures and server assertions confined to disposable worlds using the opt-in debug jar. */
public final class GuiDebugFixtures {
    public static void onJoin(ServerPlayer player) {
        if (player.getGameProfile().getName().startsWith("Gui")) player.getServer().getPlayerList().op(player.getGameProfile());
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("guiqa").requires(s -> s.hasPermission(2))
            .then(Commands.literal("setup").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var guild = GuildPersistentState.get(player.getServer());
                var data = GuildService.data(player);
                data.coins = 50000; data.completedTotal = 5000; data.loan = data.debt = 0;
                data.delinquent = data.adminDelinquent = false;
                data.activeQuests.clear(); data.completedQuestIds.clear(); data.mailbox.clear();
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 12));
                player.getInventory().setItem(1, new ItemStack(ModItems.COIN, 64));
                player.getInventory().setItem(2, new ItemStack(ModItems.COIN, 36));
                player.getInventory().selected = 0;
                var cfg = ConfigManager.config();
                cfg.bossEventEnabled = false;
                cfg.auctionMaxListingsPerPlayer = 64;
                cfg.consumerLoanInterestPercent = 0;
                for (int i = 0; i < 35; i++) {
                    var saved = guild.get(new UUID(12, i)); saved.playerName = "OfflineGui" + String.format("%02d", i);
                    saved.coins = 1000 + i; saved.loan = 100;
                }
                guild.auctions.clear();
                for (int i = 0; i < 40; i++) {
                    AuctionListing listing = new AuctionListing();
                    listing.id = guild.nextAuctionId++;
                    listing.sellerId = new UUID(12, 0); listing.sellerName = "OfflineGui00";
                    listing.item = new ItemStack(i % 2 == 0 ? Items.EMERALD : Items.IRON_INGOT, i % 16 + 1);
                    listing.mode = i % 2 == 0 ? AuctionListing.MODE_BUYOUT : AuctionListing.MODE_AUCTION;
                    listing.price = 10; listing.createdAt = System.currentTimeMillis();
                    listing.expireAt = System.currentTimeMillis() + 3600000; guild.auctions.add(listing);
                }
                data.mailbox.add(new ItemStack(Items.APPLE, 4));
                guild.setDirty();
                return 1;
            }))
            .then(Commands.literal("deposit").executes(context -> {
                var p = context.getSource().getPlayerOrException(); BlockPos pos = p.blockPosition().offset(2,0,0);
                p.serverLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                p.serverLevel().setBlockAndUpdate(pos, ModBlocks.GUILD_DEPOSIT.defaultBlockState());
                var block = (GuildDepositBlockEntity) p.serverLevel().getBlockEntity(pos);
                block.setOwner(p.getUUID(), p.getGameProfile().getName()); DepositSettingsScreenHandler.open(p, block); return 1;
            }))
            .then(Commands.literal("bankfree").executes(context -> {
                BankScreenHandler.openFromBlock(context.getSource().getPlayerOrException()); return 1;
            }))
            .then(Commands.literal("normalchest").executes(context -> {
                var p = context.getSource().getPlayerOrException();
                p.openMenu(new SimpleMenuProvider((id, inv, ignored) -> ChestMenu.threeRows(id, inv, new SimpleContainer(27)),
                        Component.literal("冒険者ギルド - クエスト"))); return 1;
            }))
            .then(Commands.literal("security").executes(context -> {
                var p = context.getSource().getPlayerOrException(); var m = p.containerMenu;
                if (!(m instanceof AdminScreenHandler admin)) throw new IllegalStateException("Expected admin menu");
                var before = GuildService.data(p).toNbt(); p.getServer().getPlayerList().deop(p.getGameProfile());
                try {
                    if (admin.setGuiAmount(99999)) throw new IllegalStateException("Revoked permission accepted amount");
                    admin.clicked(33, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
                    if (!before.equals(GuildService.data(p).toNbt()) || p.containerMenu == m)
                        throw new IllegalStateException("Revoked permission changed data");
                    Files.writeString(Path.of("gui-security.txt"), "PASS revoked operator cannot edit or confirm admin actions\n");
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
                finally { p.getServer().getPlayerList().op(p.getGameProfile()); }
                return 1;
            }))
            .then(Commands.literal("persist").executes(context -> {
                var p = context.getSource().getPlayerOrException();
                try { Files.writeString(Path.of("gui-wallet-before-restart.txt"), GuildService.data(p).toNbt().toString()); }
                catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                return 1;
            })));
    }
}
