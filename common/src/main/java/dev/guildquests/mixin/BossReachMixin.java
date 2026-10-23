package dev.guildquests.mixin;

import dev.guildquests.service.BossReach;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends the melee attack range of emergency bosses by the tier bonus (in blocks). */
@Mixin(Mob.class)
public abstract class BossReachMixin {
    @Inject(method = "getMeleeAttackRangeSqr", at = @At("RETURN"), cancellable = true)
    private void guildquests$extendBossReach(LivingEntity target, CallbackInfoReturnable<Double> cir) {
        double bonus = BossReach.bonus((LivingEntity) (Object) this);
        if (bonus > 0) {
            double base = Math.sqrt(Math.max(0, cir.getReturnValue()));
            cir.setReturnValue((base + bonus) * (base + bonus));
        }
    }
}
