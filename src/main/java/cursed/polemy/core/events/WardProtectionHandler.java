package cursed.polemy.core.events;

import cursed.polemy.core.entity.mobs.WardRune;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "polemy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WardProtectionHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;

        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        List<WardRune> runes = victim.level().getEntitiesOfClass(
                WardRune.class,
                victim.getBoundingBox().inflate(2.0D, 3.0D, 2.0D),
                rune -> rune.isAlive() && rune.getCharges() > 0 && rune.isAttachedTo(victim)
        );

        if (!runes.isEmpty()) {
            runes.get(0).consumeCharge();
            event.setCanceled(true); // Completely blocks the hit
        }
    }
}
