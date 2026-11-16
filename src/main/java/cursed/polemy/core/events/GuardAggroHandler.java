package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.core.entity.mobs.Lares;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardAggroHandler {

    @SubscribeEvent
    public static void onMonsterJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        if (event.getEntity() instanceof Raider raider) {
            raider.targetSelector.addGoal(1,
                    new NearestAttackableTargetGoal<>(raider, AbstractVillagerExtra.class, true));
        } else if (event.getEntity() instanceof Monster monster && isStandardHostile(monster)) {
            monster.targetSelector.addGoal(1,
                    new NearestAttackableTargetGoal<>(monster, AbstractVillagerExtra.class, true));
        }
    }

    @SubscribeEvent
    public static void onTargetChange(LivingChangeTargetEvent event) {
        if (event.getEntity().level().isClientSide()) return;

        if (event.getEntity() instanceof net.minecraft.world.entity.animal.IronGolem
                && (event.getNewTarget() instanceof AbstractVillagerExtra || event.getNewTarget() instanceof Lares)) {
            event.setCanceled(true);
            return;
        }

        if ((event.getEntity() instanceof Monster || event.getEntity() instanceof Raider)
                && event.getNewTarget() instanceof AbstractVillager) {

            Mob attacker = (Mob) event.getEntity();

            List<AbstractVillagerExtra> nearbyGuards = attacker.level().getEntitiesOfClass(
                    AbstractVillagerExtra.class,
                    attacker.getBoundingBox().inflate(16.0D),
                    guard -> guard.isAlive() && attacker.hasLineOfSight(guard)
            );

            if (!nearbyGuards.isEmpty()) {
                nearbyGuards.sort(Comparator.comparingDouble(attacker::distanceToSqr));
                event.setNewTarget(nearbyGuards.get(0));
            }
        }
    }

    private static boolean isStandardHostile(Monster monster) {
        return !(monster instanceof EnderMan)
                && !(monster instanceof ZombifiedPiglin)
                && !(monster instanceof Creeper);
    }
}
