package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Emergency boss event: while players are online, every configured interval a chance roll
 * spawns a buffed random overworld hostile mob 50-100 blocks from a random player, shown on
 * a boss bar. On death the configured reward is split among players by damage share.
 */
public final class BossEvent {
    private static final Random RANDOM = new Random();
    private static final String BOSS_TAG = "guildquests_emergency_boss";
    private static final int TICKS_PER_MINUTE = 1200;
    /** S2C: show/clear the client-side boss pin (x,y,z + lifetime ticks; lifetime 0 = clear). */
    public static final Identifier BOSS_PIN = new Identifier("guildquests", "boss_pin");

    private static final List<EntityType<?>> BOSSES = List.of(
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
            EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
            EntityType.EVOKER, EntityType.RAVAGER);

    /** Boss weapon: always diamond tier (sword or axe). */
    private static final Item[] WEAPONS = {Items.DIAMOND_SWORD, Items.DIAMOND_AXE};

    /** Full diamond armor, always all four slots. */
    private static final Item[] ARMOR_SET = {
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static int ticker;
    private static LivingEntity boss;
    private static ServerBossBar bar;
    private static String bossName = "";
    private static int markerLife;
    private static double lastStuckX, lastStuckY, lastStuckZ;
    private static int stuckTicks;
    private static final Map<UUID, ServerPlayerEntity> viewers = new HashMap<>();
    private static final Map<UUID, Double> damage = new HashMap<>();
    private static final Map<UUID, String> damageNames = new HashMap<>();

    private BossEvent() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BossEvent::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            // Environmental kill cheese (lava, void, fall, etc.) is ignored for the boss.
            if (entity.getCommandTags().contains(BOSS_TAG) && isEnvironmental(source)) {
                return false;
            }
            onDamage(entity, source, amount);
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(BossEvent::onDeath);
    }

    private static boolean isEnvironmental(DamageSource source) {
        return source.isOf(DamageTypes.LAVA)
                || source.isOf(DamageTypes.IN_FIRE)
                || source.isOf(DamageTypes.ON_FIRE)
                || source.isOf(DamageTypes.OUT_OF_WORLD)
                || source.isOf(DamageTypes.FALL)
                || source.isOf(DamageTypes.DROWN)
                || source.isOf(DamageTypes.CACTUS)
                || source.isOf(DamageTypes.HOT_FLOOR)
                || source.isOf(DamageTypes.FREEZE)
                || source.isOf(DamageTypes.STALAGMITE)
                || source.isOf(DamageTypes.SWEET_BERRY_BUSH);
    }

    private static void tick(MinecraftServer server) {
        ModConfig config = ConfigManager.config();
        if (!config.bossEventEnabled) {
            ticker = 0;
            return;
        }
        boolean anyoneOnline = !server.getPlayerManager().getPlayerList().isEmpty();
        if (anyoneOnline) ticker++;
        updateBar(server);

        // Rescue the boss if it somehow ends up in lava, the void, walled in, or stuck.
        if (boss != null && !boss.isRemoved() && boss.isAlive()) {
            ServerWorld world = boss.getWorld() instanceof ServerWorld sw ? sw : null;
            if (world != null) {
                boolean needsRescue = false;

                // Lava / void.
                if (boss.isInLava() || boss.getY() < -64) {
                    needsRescue = true;
                }

                // Walled in: the block at the boss's feet is a full solid cube.
                if (!needsRescue) {
                    var state = world.getBlockState(boss.getBlockPos());
                    if (state.isSolidBlock(world, boss.getBlockPos())) {
                        needsRescue = true;
                    }
                }

                // Stuck: has not moved for ~5 seconds (100 ticks).
                if (!needsRescue) {
                    double dx = boss.getX() - lastStuckX;
                    double dy = boss.getY() - lastStuckY;
                    double dz = boss.getZ() - lastStuckZ;
                    if (dx * dx + dy * dy + dz * dz < 0.01) {
                        stuckTicks++;
                        if (stuckTicks >= 100) {
                            needsRescue = true;
                        }
                    } else {
                        stuckTicks = 0;
                        lastStuckX = boss.getX();
                        lastStuckY = boss.getY();
                        lastStuckZ = boss.getZ();
                    }
                }

                if (needsRescue) {
                    ServerPlayerEntity target = nearestPlayer(boss);
                    int tx;
                    int tz;
                    if (target != null) {
                        tx = MathHelper.floor(target.getX());
                        tz = MathHelper.floor(target.getZ());
                    } else {
                        tx = MathHelper.floor(boss.getX());
                        tz = MathHelper.floor(boss.getZ());
                    }
                    int ty = world.getTopY(Heightmap.Type.MOTION_BLOCKING, tx, tz);
                    boss.teleport(tx + 0.5, ty + 0.1, tz + 0.5, false);
                    boss.extinguish();
                    boss.fallDistance = 0;
                    stuckTicks = 0;
                    lastStuckX = boss.getX();
                    lastStuckY = boss.getY();
                    lastStuckZ = boss.getZ();
                }
            }
        }

        // Tick down boss pin lifetime.
        if (markerLife > 0) {
            markerLife--;
            if (markerLife == 0) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    sendBossPin(p, 0, 0, 0, 0);
                }
            }
        }

        int interval = Math.max(1, Math.min(1440, config.bossEventIntervalMinutes)) * TICKS_PER_MINUTE;
        if (ticker < interval) return;
        ticker = 0;
        if (!anyoneOnline) return;
        if (boss != null && !boss.isRemoved() && boss.isAlive()) return;
        int chance = Math.max(0, Math.min(100, config.bossEventChancePercent));
        if (RANDOM.nextInt(100) >= chance) return;
        spawnNow(server);
    }

    /**
     * Spawns the boss immediately with the normal placement rules (50-100 blocks from a
     * random online player), skipping the chance roll. Returns false when a boss is already
     * alive, no player is online, or the spawn could not be completed.
     */
    public static boolean spawnNow(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return false;

        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof LivingEntity living && living.isAlive()
                    && living.getCommandTags().contains(BOSS_TAG)) {
                adopt(living);
                return false;
            }
        }

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return false;
        ServerPlayerEntity anchor = players.get(RANDOM.nextInt(players.size()));
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = 50 + RANDOM.nextDouble() * 50;
        int x = MathHelper.clamp((int) Math.floor(anchor.getX() + Math.cos(angle) * distance),
                -29999000, 29999000);
        int z = MathHelper.clamp((int) Math.floor(anchor.getZ() + Math.sin(angle) * distance),
                -29999000, 29999000);
        world.getChunk(x >> 4, z >> 4);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);

        EntityType<?> type = BOSSES.get(RANDOM.nextInt(BOSSES.size()));
        if (!(type.create(world) instanceof MobEntity mob)) return false;

        ModConfig config = ConfigManager.config();
        String mobName = type.getName().getString();
        bossName = "【緊急ボス】" + mobName;
        mob.refreshPositionAndAngles(x + 0.5, y, z + 0.5, RANDOM.nextFloat() * 360.0f, 0.0f);
        mob.setCustomName(Text.literal(bossName).formatted(Formatting.RED));
        mob.setCustomNameVisible(true);
        mob.addCommandTag(BOSS_TAG);
        mob.setPersistent();

        double health = Math.max(1, config.bossEventHealth);
        if (mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null) {
            mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(health);
        }
        if (mob.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            mob.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }
        mob.setHealth((float) health);
        equip(mob);
        world.spawnEntity(mob);

        // Show a client-rendered pin at the boss location (5 minutes or until death).
        damage.clear();
        damageNames.clear();
        adopt(mob);
        markerLife = 6000;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendBossPin(p, x, y, z, markerLife);
        }
        GuildService.broadcast(server, "緊急ボス【" + mobName + "】が出現！座標: (" + x + ", " + y + ", " + z
                + ")  与えたダメージの割合で報酬 " + Math.max(0, config.bossEventReward)
                + " コインを分配します。", Formatting.RED);
        return true;
    }

    private static ServerPlayerEntity nearestPlayer(LivingEntity from) {
        MinecraftServer server = from.getWorld().getServer();
        if (server == null) return null;
        ServerPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isRemoved() || !p.isAlive()) continue;
            double d = p.squaredDistanceTo(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Sends the boss pin packet to one player. lifetimeTicks 0 clears the pin. */
    private static void sendBossPin(ServerPlayerEntity player, int x, int y, int z, int lifetimeTicks) {
        if (!ServerPlayNetworking.canSend(player, BOSS_PIN)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeVarInt(lifetimeTicks);
        ServerPlayNetworking.send(player, BOSS_PIN, buf);
    }

    private static void clearBossPins(MinecraftServer server) {
        markerLife = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendBossPin(p, 0, 0, 0, 0);
        }
    }

    /** Equips the boss with a unified, always-full enchanted diamond set. */
    private static void equip(MobEntity mob) {
        Item weaponItem = WEAPONS[RANDOM.nextInt(WEAPONS.length)];
        ItemStack weapon = new ItemStack(weaponItem);
        weapon.addEnchantment(Enchantments.SHARPNESS, 5);
        weapon.addEnchantment(Enchantments.FIRE_ASPECT, 2);
        weapon.addEnchantment(Enchantments.UNBREAKING, 3);
        if (weaponItem instanceof SwordItem) {
            weapon.addEnchantment(Enchantments.KNOCKBACK, 2);
            weapon.addEnchantment(Enchantments.SWEEPING, 2);
        }
        mob.equipStack(EquipmentSlot.MAINHAND, weapon);
        mob.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);

        for (int i = 0; i < ARMOR_SET.length; i++) {
            ItemStack armor = new ItemStack(ARMOR_SET[i]);
            armor.addEnchantment(Enchantments.PROTECTION, 4);
            armor.addEnchantment(Enchantments.THORNS, 3);
            armor.addEnchantment(Enchantments.UNBREAKING, 3);
            mob.equipStack(ARMOR_SLOTS[i], armor);
            mob.setEquipmentDropChance(ARMOR_SLOTS[i], 0f);
        }
    }

    private static void onDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!entity.getCommandTags().contains(BOSS_TAG)) return;
        if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) return;
        damage.merge(attacker.getUuid(), (double) amount, Double::sum);
        damageNames.put(attacker.getUuid(), attacker.getName().getString());
        if (boss == null || boss.isRemoved() || !boss.isAlive()) adopt(entity);
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (!entity.getCommandTags().contains(BOSS_TAG)) return;
        MinecraftServer server = entity.getWorld().getServer();
        if (server == null) return;
        clearBossPins(server);
        payout(server, bossName.isEmpty() ? entity.getDisplayName().getString() : bossName);
    }

    private static void payout(MinecraftServer server, String name) {
        Map<UUID, Double> hits = new HashMap<>(damage);
        double total = 0;
        for (double value : hits.values()) total += value;
        long reward = Math.max(0, ConfigManager.config().bossEventReward);

        if (hits.isEmpty() || total <= 0 || reward == 0) {
            GuildService.broadcast(server, "緊急ボス【" + name
                    + "】が倒された（有効ダメージがないため報酬の分配はありません）", Formatting.YELLOW);
        } else {
            List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(hits.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            long[] shares = new long[sorted.size()];
            long given = 0;
            for (int i = 1; i < sorted.size(); i++) {
                shares[i] = (long) Math.floor(reward * sorted.get(i).getValue() / total);
                given += shares[i];
            }
            shares[0] = reward - given;

            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                UUID uuid = sorted.get(i).getKey();
                double dealt = sorted.get(i).getValue();
                if (shares[i] > 0) {
                    GuildService.creditCoins(server, uuid, shares[i]);
                    ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
                    if (online != null) {
                        GuildService.message(online, "緊急ボス討伐報酬: +" + shares[i] + " コイン（与ダメージ: "
                                + (int) Math.round(dealt) + "）", Formatting.GREEN);
                    }
                }
                if (detail.length() > 0) detail.append(" / ");
                detail.append(damageNames.getOrDefault(uuid, uuid.toString().substring(0, 8)))
                        .append(" ").append(shares[i]).append("コイン");
            }
            GuildService.broadcast(server, "緊急ボス【" + name + "】を討伐！報酬 " + reward
                    + " コインを分配: " + detail, Formatting.GOLD);
        }
        damage.clear();
        damageNames.clear();
        clearBoss();
    }

    private static void adopt(LivingEntity entity) {
        boss = entity;
        if (entity.getCustomName() != null) {
            bossName = entity.getCustomName().getString();
        }
        // Ensure adopted bosses also resist knockback (anti-cheese).
        if (entity.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            entity.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }
        viewers.clear();
        bar = new ServerBossBar(Text.literal(bossName).formatted(Formatting.RED),
                BossBar.Color.RED, BossBar.Style.PROGRESS);
        // Always glowing so the emergency boss is easy to spot.
        entity.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, Integer.MAX_VALUE, 0, false, false, false));
        if (boss.getHealth() > 0) {
            bar.setPercent(MathHelper.clamp(boss.getHealth() / boss.getMaxHealth(), 0f, 1f));
        }
    }

    private static void updateBar(MinecraftServer server) {
        if (bar == null) return;
        if (boss == null || boss.isRemoved() || !boss.isAlive()) {
            clearBoss();
            return;
        }
        bar.setPercent(MathHelper.clamp(boss.getHealth() / boss.getMaxHealth(), 0f, 1f));

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayerEntity tracked = viewers.get(player.getUuid());
            if (tracked == null) {
                bar.addPlayer(player);
                viewers.put(player.getUuid(), player);
            } else if (tracked != player) {
                bar.removePlayer(tracked);
                bar.addPlayer(player);
                viewers.put(player.getUuid(), player);
            }
        }
        Iterator<Map.Entry<UUID, ServerPlayerEntity>> iterator = viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ServerPlayerEntity> entry = iterator.next();
            if (server.getPlayerManager().getPlayer(entry.getKey()) == null) {
                bar.removePlayer(entry.getValue());
                iterator.remove();
            }
        }
    }

    private static void clearBoss() {
        if (bar != null) bar.clearPlayers();
        bar = null;
        boss = null;
        viewers.clear();
    }
}
