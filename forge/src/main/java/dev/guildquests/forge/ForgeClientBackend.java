package dev.guildquests.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.platform.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge implementation of the loader-neutral {@link PlatformClient.ClientBackend}.
 * <p>
 * Created during mod construction (see {@link ForgeClientBootstrap}) because Forge fires
 * {@link RegisterKeyMappingsEvent} before {@code FMLClientSetupEvent}.
 */
final class ForgeClientBackend implements PlatformClient.ClientBackend {
    private final List<BoundKey> keys = new ArrayList<>();
    private boolean keysRegistered;
    private Runnable disconnectHandler;
    private PlatformClient.WorldRenderHandler worldRenderHandler;
    private java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> clientTickHandler;

    ForgeClientBackend(IEventBus modBus) {
        modBus.addListener(this::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ClientTickEvent e) -> onClientTick(e));
        MinecraftForge.EVENT_BUS.addListener(this::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderLevel);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (keysRegistered) {
            return;
        }
        keysRegistered = true;
        for (BoundKey bound : keys) {
            event.register(bound.key());
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (BoundKey bound : keys) {
            while (bound.key().consumeClick()) {
                bound.onPress().run();
            }
        }
        if (clientTickHandler != null) {
            clientTickHandler.accept(Minecraft.getInstance().screen);
        }
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (disconnectHandler != null) {
            disconnectHandler.run();
        }
    }

    private void onRenderLevel(RenderLevelStageEvent event) {
        if (worldRenderHandler == null
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        WorldRenderContext context = new ForgeWorldRenderContext(event.getCamera(), event.getPoseStack());
        worldRenderHandler.render(context);
    }

    @Override
    public void onBlockRenderCutout(Block block) {
        ItemBlockRenderTypes.setRenderLayer(block, RenderType.cutout());
    }

    @Override
    public KeyMapping registerKeyBinding(String translationKey, int defaultKey, Runnable onPress) {
        KeyMapping key = new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey,
                "key.categories.guildquests");
        keys.add(new BoundKey(key, onPress));
        return key;
    }

    @Override
    public void sendOpenGuild(ResourceLocation channel) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        if (!ForgeNetwork.channel().isRemotePresent(connection.getConnection())) {
            return;
        }
        ForgeNetwork.channel().sendToServer(new ForgeNetwork.OpenGuildC2S());
    }

    @Override
    public void onBossPin(PlatformClient.BossPinHandler handler) {
        ForgeNetwork.setBossPinSink(handler::accept);
    }

    @Override
    public void onDisconnect(Runnable handler) {
        this.disconnectHandler = handler;
    }

    @Override
    public void onClientTick(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> handler) {
        this.clientTickHandler = handler;
    }

    @Override
    public void onWorldRender(PlatformClient.WorldRenderHandler handler) {
        this.worldRenderHandler = handler;
    }

    private record BoundKey(KeyMapping key, Runnable onPress) {
    }

    private record ForgeWorldRenderContext(Camera camera, PoseStack poseStack) implements WorldRenderContext {
    }
}
