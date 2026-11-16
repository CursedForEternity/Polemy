package cursed.polemy.core.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cursed.polemy.core.client.models.WardModel;
import cursed.polemy.core.entity.mobs.WardRune;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public class WardRuneRenderer extends EntityRenderer<WardRune> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("polemy", "textures/entity/ward.png");

    private final WardModel<WardRune> model;

    public WardRuneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new WardModel<>(context.bakeLayer(WardModel.LAYER_LOCATION));
    }

    @Override
    public void render(WardRune entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // Blockbench models render upside-down in Minecraft unless flipped and offset
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0D, -0.15D, 0.0D);

        float ageInTicks = entity.tickCount + partialTicks;
        this.model.setupAnim(entity, 0.0F, 0.0F, ageInTicks, 0.0F, 0.0F);

        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityTranslucent(this.getTextureLocation(entity)));
        this.model.renderToBuffer(
                poseStack,
                vertexConsumer,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 0.85F
        );

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(WardRune entity) {
        return TEXTURE;
    }
}
