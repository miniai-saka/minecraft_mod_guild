package dev.guildquests.service;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Emergency bosses switch to a bow while the target is out of melee reach, and let the normal
 * melee goal take over again once the player closes in.
 */
public final class BossRangedGoal extends Goal {
    private final Mob mob;
    private final double minRange;
    private final double maxRange;
    private final int interval;
    private final double damage;
    private int cooldown;
    private net.minecraft.world.item.ItemStack previousHand;

    public BossRangedGoal(Mob mob, double minRange, double maxRange, int interval, double damage) {
        this.mob = mob;
        this.minRange = minRange;
        this.maxRange = maxRange;
        this.interval = Math.max(5, interval);
        this.damage = Math.max(0, damage);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return canShoot();
    }

    @Override
    public boolean canContinueToUse() {
        return canShoot();
    }

    private boolean canShoot() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        double distance = mob.distanceTo(target);
        return distance > minRange && distance <= maxRange && mob.hasLineOfSight(target);
    }

    @Override
    public void start() {
        cooldown = interval / 2;
        mob.getNavigation().stop();
        // Hold a bow while shooting; the melee weapon comes back when the player closes in.
        previousHand = mob.getMainHandItem().copy();
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        mob.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0f);
    }

    @Override
    public void stop() {
        cooldown = 0;
        if (previousHand != null) {
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, previousHand);
            previousHand = null;
        }
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0f, 30.0f);
        mob.getNavigation().stop();
        if (--cooldown > 0) {
            return;
        }
        cooldown = interval;
        shoot(target);
    }

    private void shoot(LivingEntity target) {
        Arrow arrow = new Arrow(mob.level(), mob);
        arrow.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
        Vec3 aim = new Vec3(target.getX() - mob.getX(),
                target.getY(0.333) - arrow.getY(),
                target.getZ() - mob.getZ());
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        arrow.shoot(aim.x, aim.y + horizontal * 0.2, aim.z, 1.6f, 4.0f);
        arrow.setBaseDamage(damage);
        mob.level().addFreshEntity(arrow);
        mob.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f);
    }
}
