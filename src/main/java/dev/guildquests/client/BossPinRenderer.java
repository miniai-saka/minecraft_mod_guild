package dev.guildquests.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
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
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BossPinRenderer::render);
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        if (!client.world.getRegistryKey().getValue().toString().equals("minecraft:overworld")) return;

        if (life > 0 && client.world.getTime() % 2 == 0) life--;
        if (life <= 0) {
            active = false;
            return;
        }

        float alpha = life < 200 ? life / 200f : 1.0f;
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) return;

        matrices.push();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        float rot = (System.currentTimeMillis() % 36000L) / 100f;
        matrices.multiply(new org.joml.Quaternionf().fromAxisAngleDeg(new Vector3f(0, 1, 0), rot));
        drawBeam(matrices, alpha);
        matrices.pop();
    }

    private static void drawBeam(MatrixStack matrices, float alpha) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        float half = 0.15f;
        float top = 128f;
        float r = 1f, g = 0.15f, b = 0.15f;
        float baseA = 0.35f * alpha;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableCull();

        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        // -Z
        buffer.vertex(matrix, -half, 0, -half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, 0, -half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, top, -half).color(r, g, b, 0f).next();
        buffer.vertex(matrix, -half, top, -half).color(r, g, b, 0f).next();
        // +Z
        buffer.vertex(matrix, -half, 0, half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, 0, half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, top, half).color(r, g, b, 0f).next();
        buffer.vertex(matrix, -half, top, half).color(r, g, b, 0f).next();
        // -X
        buffer.vertex(matrix, -half, 0, -half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, -half, 0, half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, -half, top, half).color(r, g, b, 0f).next();
        buffer.vertex(matrix, -half, top, -half).color(r, g, b, 0f).next();
        // +X
        buffer.vertex(matrix, half, 0, -half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, 0, half).color(r, g, b, baseA).next();
        buffer.vertex(matrix, half, top, half).color(r, g, b, 0f).next();
        buffer.vertex(matrix, half, top, -half).color(r, g, b, 0f).next();
        tessellator.draw();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
