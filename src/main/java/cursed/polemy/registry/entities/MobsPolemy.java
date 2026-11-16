package cursed.polemy.registry.entities;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.mobs.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class MobsPolemy {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Polemy.MODID);

    public static final RegistryObject<EntityType<Bailiff>> BAILIFF =
            ENTITY_TYPES.register("bailiff", () -> EntityType.Builder
                    .of(Bailiff::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .build("bailiff"));
    public static final RegistryObject<EntityType<Sentry>> SENTRY =
            ENTITY_TYPES.register("sentry", () -> EntityType.Builder
                    .of(Sentry::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .build("sentry"));
    public static final RegistryObject<EntityType<Invoker>> INVOKER =
            ENTITY_TYPES.register("invoker", () -> EntityType.Builder
                    .of(Invoker::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .build("invoker"));
    public static final RegistryObject<EntityType<Lares>> LARES =
            ENTITY_TYPES.register("lare", () -> EntityType.Builder
                    .of(Lares::new, MobCategory.CREATURE)
                    .sized(0.5F, 0.5F)
                    .build("lare"));
    public static final RegistryObject<EntityType<WardRune>> WARD_RUNE =
            ENTITY_TYPES.register("ward_rune", () -> EntityType.Builder
                    .of(WardRune::new, MobCategory.MISC)
                    .sized(1.5F, 0.1F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("ward_rune"));

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
