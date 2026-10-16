package dev.guildquests.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.platform.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-side boss pin: a rotating red beacon beam at the boss spawn.
 * Driven by the guildquests:boss_pin S2C packet (lifetime 0 clears).
 */
public final class BossPinRenderer {
    private static final int LIFETIME_TICKS = 6000;
    private static boolean active;
    private static double x, y, z;
    private static int life;

    private BossPinRenderer() {
    }

    public static void init() {
        PlatformClient.onWorldRender(BossPinRenderer::render);
    }

    public static void set(int px, int py, int pz, int lifetimeTicks) {
        if (lifetimeTicks <= 0) {
            active = false;
            return;
        }
        x = px + 0.5;
        y = py;
        z = pz + 0.5;
        life = Math.min(lifetimeTicks, LIFETIME_TICKS);
        active = true;
    }

    public static void clear() {
        active = false;
    }

    private static void render(WorldRenderContext context) {
        if (!active) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (!client.level.dimension().location().toString().equals("minecraft:overworld")) return;

        if (life > 0 && client.level.getGameTime() % 2 == 0) life--;
        if (life <= 0) {
            active = false;
            return;
        }

        float alpha = life < 200 ? life / 200f : 1.0f;
        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        PoseStack matrices = context.poseStack();
        if (matrices == null) return;

        matrices.pushPose();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        float rot = (System.currentTimeMillis() % 36000L) / 100f;
        matrices.mulPose(new org.joml.Quaternionf().fromAxisAngleDeg(new Vector3f(0, 1, 0), rot));
        drawBeam(matrices, alpha);
        matrices.popPose();
    }

    private static void drawBeam(PoseStack matrices, float alpha) {
        Matrix4f matrix = matrices.last().pose();
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder buffer = tessellator.getBuilder();
        float half = 0.15f;
        float top = 128f;
        float r = 1f, g = 0.15f, b = 0.15f;
        float baseA = 0.35f * alpha;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // -Z
        buffer.vertex(matrix, -half, 0, -half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, 0, -half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, top, -half).color(r, g, b, 0f).endVertex();
        buffer.vertex(matrix, -half, top, -half).color(r, g, b, 0f).endVertex();
        // +Z
        buffer.vertex(matrix, -half, 0, half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, 0, half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, top, half).color(r, g, b, 0f).endVertex();
        buffer.vertex(matrix, -half, top, half).color(r, g, b, 0f).endVertex();
        // -X
        buffer.vertex(matrix, -half, 0, -half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, -half, 0, half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, -half, top, half).color(r, g, b, 0f).endVertex();
        buffer.vertex(matrix, -half, top, -half).color(r, g, b, 0f).endVertex();
        // +X
        buffer.vertex(matrix, half, 0, -half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, 0, half).color(r, g, b, baseA).endVertex();
        buffer.vertex(matrix, half, top, half).color(r, g, b, 0f).endVertex();
        buffer.vertex(matrix, half, top, -half).color(r, g, b, 0f).endVertex();
        tessellator.end();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
