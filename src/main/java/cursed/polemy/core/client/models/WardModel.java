package cursed.polemy.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cursed.polemy.Polemy;
import cursed.polemy.core.entity.mobs.WardRune;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;

public class WardModel<T extends WardRune> extends EntityModel<T> {
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(new ResourceLocation(Polemy.MODID, "ward"), "main");

    private final ModelPart ward2;
    private final ModelPart ward1;
    private final ModelPart ward;

    public WardModel(ModelPart root) {
        this.ward2 = root.getChild("ward2");
        this.ward1 = root.getChild("ward1");
        this.ward = root.getChild("ward");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("ward2", CubeListBuilder.create()
                .texOffs(-24, 0).addBox(-12.0F, 0.0F, -12.0F, 24.0F, 0.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -6.0F, 0.0F));

        PartDefinition ward1 = partdefinition.addOrReplaceChild("ward1", CubeListBuilder.create(),
                PartPose.offset(0.0F, -3.0F, 0.0F));

        ward1.addOrReplaceChild("cube_r1", CubeListBuilder.create()
                .texOffs(-24, 0).addBox(-12.0F, 0.0F, -12.0F, 24.0F, 0.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, -0.7854F, 0.0F));

        partdefinition.addOrReplaceChild("ward", CubeListBuilder.create()
                .texOffs(-24, 0).addBox(-12.0F, -0.009F, -12.0F, 24.0F, 0.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -1F, 0.0F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        int charges = entity.getCharges();

        // Layer visibility matches remaining hit charges (3 layers -> 2 layers -> 1 layer)
        this.ward.visible = charges >= 1;
        this.ward1.visible = charges >= 2;
        this.ward2.visible = charges >= 3;

        // Counter-rotate the layers slowly for an arcane effect
        this.ward.yRot = ageInTicks * 0.04F;
        this.ward1.yRot = -ageInTicks * 0.06F;
        this.ward2.yRot = ageInTicks * 0.08F;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        ward2.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        ward1.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        ward.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
