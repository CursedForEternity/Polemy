package cursed.polemy.core.entity;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.mobs.Lares;
import cursed.polemy.core.entity.mobs.Rogue;
import cursed.polemy.core.entity.mobs.Sentry;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEventBusEvents {
    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(MobsPolemy.BAILIFF.get(), AbstractVillagerExtra.createCombatAttributes().build());
        event.put(MobsPolemy.INVOKER.get(), AbstractVillagerExtra.createCombatAttributes().build());
        event.put(MobsPolemy.SENTRY.get(), Sentry.createSentryAttributes().build());
        event.put(MobsPolemy.LARES.get(), Lares.createAttributes().build());
        event.put(MobsPolemy.ROGUE.get(), Rogue.createRogueAttributes().build());
    }
}