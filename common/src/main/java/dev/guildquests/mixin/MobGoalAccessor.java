package dev.guildquests.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Mob.goalSelector so bosses can gain the ranged attack goal at spawn. */
@Mixin(Mob.class)
public interface MobGoalAccessor {
    @Accessor("goalSelector")
    GoalSelector guildquests$goalSelector();
}
