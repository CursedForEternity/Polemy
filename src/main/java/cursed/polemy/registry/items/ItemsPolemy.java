package cursed.polemy.registry.items;

import cursed.polemy.Polemy;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ItemsPolemy {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Polemy.MODID);

    public static final RegistryObject<Item> BAILIFF_SPAWN_EGG = ITEMS.register("bailiff_spawn_egg",
            () -> new ForgeSpawnEggItem(MobsPolemy.BAILIFF, 0x7A838A, 0x2F363B, new Item.Properties()));

    public static final RegistryObject<Item> SENTRY_SPAWN_EGG = ITEMS.register("sentry_spawn_egg",
            () -> new ForgeSpawnEggItem(MobsPolemy.SENTRY, 0x4E6B47, 0x7A5230, new Item.Properties()));

    public static final RegistryObject<Item> INVOKER_SPAWN_EGG = ITEMS.register("invoker_spawn_egg",
            () -> new ForgeSpawnEggItem(MobsPolemy.INVOKER, 0x3B2D63, 0xE5B83B, new Item.Properties()));

    public static final RegistryObject<Item> LARES_SPAWN_EGG = ITEMS.register("lare_spawn_egg",
            () -> new ForgeSpawnEggItem(MobsPolemy.LARES, 0xd9cf1a, 0x9BE3E8, new Item.Properties()));


    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
