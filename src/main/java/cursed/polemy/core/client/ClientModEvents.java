package cursed.polemy.core.client;

import cursed.polemy.Polemy;
import cursed.polemy.core.client.models.GuardVillagerModel;
import cursed.polemy.core.client.models.InvokerModel;
import cursed.polemy.core.client.models.LareModel;
import cursed.polemy.core.client.models.WardModel;
import cursed.polemy.core.client.renderers.WardRuneRenderer;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.core.entity.mobs.Invoker;
import cursed.polemy.core.entity.mobs.Lares;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "polemy", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(GuardVillagerModel.BAILIFF_LAYER, GuardVillagerModel::createBodyLayer);
        event.registerLayerDefinition(GuardVillagerModel.SENTRY_LAYER, GuardVillagerModel::createBodyLayer);
        event.registerLayerDefinition(InvokerModel.LAYER_LOCATION, InvokerModel::createBodyLayer);
        event.registerLayerDefinition(LareModel.LAYER_LOCATION, LareModel::createBodyLayer);
        event.registerLayerDefinition(WardModel.LAYER_LOCATION, WardModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MobsPolemy.BAILIFF.get(), ctx -> {
            MobRenderer<AbstractVillagerExtra, GuardVillagerModel<AbstractVillagerExtra>> renderer =
                    new MobRenderer<>(ctx, new GuardVillagerModel<>(ctx.bakeLayer(GuardVillagerModel.BAILIFF_LAYER)), 0.5F) {
                        @Override
                        public ResourceLocation getTextureLocation(AbstractVillagerExtra entity) {
                            return new ResourceLocation(Polemy.MODID, "textures/entity/bailiff.png");
                        }
                    };
            renderer.addLayer(new ItemInHandLayer<>(renderer, ctx.getItemInHandRenderer()));
            return renderer;
        });

        // 2. Sentry (with ItemInHandLayer so bow renders)
        event.registerEntityRenderer(MobsPolemy.SENTRY.get(), ctx -> {
            MobRenderer<AbstractVillagerExtra, GuardVillagerModel<AbstractVillagerExtra>> renderer =
                    new MobRenderer<>(ctx, new GuardVillagerModel<>(ctx.bakeLayer(GuardVillagerModel.SENTRY_LAYER)), 0.5F) {
                        @Override
                        public ResourceLocation getTextureLocation(AbstractVillagerExtra entity) {
                            return new ResourceLocation(Polemy.MODID, "textures/entity/sentry.png");
                        }
                    };
            renderer.addLayer(new ItemInHandLayer<>(renderer, ctx.getItemInHandRenderer()));
            return renderer;
        });

        // 3. Invoker
        event.registerEntityRenderer(MobsPolemy.INVOKER.get(), ctx ->
                new MobRenderer<Invoker, InvokerModel<Invoker>>(ctx, new InvokerModel<>(ctx.bakeLayer(InvokerModel.LAYER_LOCATION)), 0.5F) {
                    @Override
                    public ResourceLocation getTextureLocation(Invoker entity) {
                        return new ResourceLocation(Polemy.MODID, "textures/entity/invoker.png");
                    }
                });


        // 4. Lares (glows in the dark like a Vex via getBlockLightLevel)
        event.registerEntityRenderer(MobsPolemy.LARES.get(), ctx ->
                new MobRenderer<Lares, LareModel<Lares>>(ctx, new LareModel<>(ctx.bakeLayer(LareModel.LAYER_LOCATION)), 0.3F) {
                    @Override
                    protected int getBlockLightLevel(Lares entity, BlockPos pos) {
                        return 15;
                    }

                    @Override
                    public ResourceLocation getTextureLocation(Lares entity) {
                        return new ResourceLocation(Polemy.MODID, "textures/entity/lare.png");
                    }
                });

        // 5. Ward Rune
        event.registerEntityRenderer(MobsPolemy.WARD_RUNE.get(), WardRuneRenderer::new);
    }
}
