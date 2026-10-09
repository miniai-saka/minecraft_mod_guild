package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
 * spawns a buffed random overworld hostile mob 200-500 blocks from a random player, shown on
 * a boss bar. On death the configured reward is split among players by damage share.
 */
public final class BossEvent {
    private static final Random RANDOM = new Random();
    private static final String BOSS_TAG = "guildquests_emergency_boss";
    private static final int TICKS_PER_MINUTE = 1200;

    private static final List<EntityType<?>> BOSSES = List.of(
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
            EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
            EntityType.EVOKER, EntityType.RAVAGER);

    private static final Item[] WEAPONS = {
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD,
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.DIAMOND_AXE};

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final Enchantment[] ARMOR_FAMILY = {
            Enchantments.PROTECTION, Enchantments.FIRE_PROTECTION,
            Enchantments.BLAST_PROTECTION, Enchantments.PROJECTILE_PROTECTION};
    private static final Enchantment[] ARMOR_EXTRAS = {Enchantments.THORNS, Enchantments.UNBREAKING};
    private static final Enchantment[] WEAPON_FAMILY = {
            Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS};
    private static final Enchantment[] SWORD_EXTRAS = {
            Enchantments.FIRE_ASPECT, Enchantments.KNOCKBACK, Enchantments.SWEEPING, Enchantments.UNBREAKING};
    private static final Enchantment[] AXE_EXTRAS = {
            Enchantments.FIRE_ASPECT, Enchantments.KNOCKBACK, Enchantments.UNBREAKING};

    private static int ticker;
    private static LivingEntity boss;
    private static ServerBossBar bar;
    private static String bossName = "";
    private static final Map<UUID, ServerPlayerEntity> viewers = new HashMap<>();
    private static final Map<UUID, Double> damage = new HashMap<>();
    private static final Map<UUID, String> damageNames = new HashMap<>();

    private BossEvent() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BossEvent::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            onDamage(entity, source, amount);
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(BossEvent::onDeath);
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

        int interval = Math.max(1, Math.min(1440, config.bossEventIntervalMinutes)) * TICKS_PER_MINUTE;
        if (ticker < interval) return;
        ticker = 0;
        if (!anyoneOnline) return;
        if (boss != null && !boss.isRemoved() && boss.isAlive()) return;
        int chance = Math.max(0, Math.min(100, config.bossEventChancePercent));
        if (RANDOM.nextInt(100) >= chance) return;
        trySpawn(server);
    }

    private static void trySpawn(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof LivingEntity living && living.isAlive()
                    && living.getCommandTags().contains(BOSS_TAG)) {
                adopt(living);
                return;
            }
        }

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;
        ServerPlayerEntity anchor = players.get(RANDOM.nextInt(players.size()));
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = 200 + RANDOM.nextDouble() * 300;
        int x = MathHelper.clamp((int) Math.floor(anchor.getX() + Math.cos(angle) * distance),
                -29999000, 29999000);
        int z = MathHelper.clamp((int) Math.floor(anchor.getZ() + Math.sin(angle) * distance),
                -29999000, 29999000);
        world.getChunk(x >> 4, z >> 4);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);

        EntityType<?> type = BOSSES.get(RANDOM.nextInt(BOSSES.size()));
        if (!(type.create(world) instanceof MobEntity mob)) return;

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
        mob.setHealth((float) health);
        equip(mob);
        world.spawnEntity(mob);

        damage.clear();
        damageNames.clear();
        adopt(mob);
        GuildService.broadcast(server, "緊急ボス【" + mobName + "】が出現！座標: (" + x + ", " + y + ", " + z
                + ")  与えたダメージの割合で報酬 " + Math.max(0, config.bossEventReward)
                + " コインを分配します。", Formatting.RED);
    }

    private static void equip(MobEntity mob) {
        ItemStack weapon = new ItemStack(WEAPONS[RANDOM.nextInt(WEAPONS.length)]);
        maybeEnchant(weapon, WEAPON_FAMILY, weapon.getItem() instanceof SwordItem ? SWORD_EXTRAS : AXE_EXTRAS);
        mob.equipStack(EquipmentSlot.MAINHAND, weapon);
        mob.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (RANDOM.nextInt(100) < 75) {
                mob.equipStack(slot, makeArmor(slot));
            }
            mob.setEquipmentDropChance(slot, 0f);
        }
    }

    private static ItemStack makeArmor(EquipmentSlot slot) {
        int tier = RANDOM.nextInt(5);
        Item item = switch (slot) {
            case HEAD -> switch (tier) {
                case 0 -> Items.LEATHER_HELMET;
                case 1 -> Items.CHAINMAIL_HELMET;
                case 2 -> Items.GOLDEN_HELMET;
                case 3 -> Items.IRON_HELMET;
                default -> Items.DIAMOND_HELMET;
            };
            case CHEST -> switch (tier) {
                case 0 -> Items.LEATHER_CHESTPLATE;
                case 1 -> Items.CHAINMAIL_CHESTPLATE;
                case 2 -> Items.GOLDEN_CHESTPLATE;
                case 3 -> Items.IRON_CHESTPLATE;
                default -> Items.DIAMOND_CHESTPLATE;
            };
            case LEGS -> switch (tier) {
                case 0 -> Items.LEATHER_LEGGINGS;
                case 1 -> Items.CHAINMAIL_LEGGINGS;
                case 2 -> Items.GOLDEN_LEGGINGS;
                case 3 -> Items.IRON_LEGGINGS;
                default -> Items.DIAMOND_LEGGINGS;
            };
            default -> switch (tier) {
                case 0 -> Items.LEATHER_BOOTS;
                case 1 -> Items.CHAINMAIL_BOOTS;
                case 2 -> Items.GOLDEN_BOOTS;
                case 3 -> Items.IRON_BOOTS;
                default -> Items.DIAMOND_BOOTS;
            };
        };
        ItemStack stack = new ItemStack(item);
        maybeEnchant(stack, ARMOR_FAMILY, ARMOR_EXTRAS);
        return stack;
    }

    /** Rolls enchant presence randomly: ~40% of items stay unenchanted. */
    private static void maybeEnchant(ItemStack stack, Enchantment[] family, Enchantment[] extras) {
        if (RANDOM.nextInt(100) < 40) return;
        Enchantment main = family[RANDOM.nextInt(family.length)];
        stack.addEnchantment(main, 1 + RANDOM.nextInt(maxLevel(main)));
        if (RANDOM.nextInt(100) < 50) {
            Enchantment extra = extras[RANDOM.nextInt(extras.length)];
            stack.addEnchantment(extra, 1 + RANDOM.nextInt(maxLevel(extra)));
        }
    }

    private static int maxLevel(Enchantment enchantment) {
        if (enchantment == Enchantments.PROTECTION || enchantment == Enchantments.FIRE_PROTECTION
                || enchantment == Enchantments.BLAST_PROTECTION || enchantment == Enchantments.PROJECTILE_PROTECTION) {
            return 4;
        }
        if (enchantment == Enchantments.SHARPNESS || enchantment == Enchantments.SMITE
                || enchantment == Enchantments.BANE_OF_ARTHROPODS) return 5;
        if (enchantment == Enchantments.UNBREAKING) return 3;
        if (enchantment == Enchantments.THORNS || enchantment == Enchantments.FIRE_ASPECT
                || enchantment == Enchantments.KNOCKBACK || enchantment == Enchantments.SWEEPING) return 2;
        return 1;
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
        viewers.clear();
        bar = new ServerBossBar(Text.literal(bossName).formatted(Formatting.RED),
                BossBar.Color.RED, BossBar.Style.PROGRESS);
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
