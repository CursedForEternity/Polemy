package cursed.polemy.core.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import cursed.polemy.core.client.VillagerExtraModel;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

public class BailiffRenderer<T extends AbstractVillagerExtra> extends MobRenderer<T, VillagerExtraModel<T>> {
    private final ResourceLocation texture;

    public BailiffRenderer(EntityRendererProvider.Context context, ResourceLocation texture) {
        super(context, new VillagerExtraModel<>(context.bakeLayer(ModelLayers.VINDICATOR)), 0.5F);
        this.texture = texture;
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()) {
            @Override
            public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                               float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                               float netHeadYaw, float headPitch) {
                if (entity.getArmPose() != AbstractVillagerExtra.GuardArmPose.CROSSED) {
                    super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
                }
            }
        });
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return this.texture;
    }
}