package cursed.polemy.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cursed.polemy.Polemy;
import cursed.polemy.core.entity.mobs.Lares;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class LareModel<T extends Lares> extends EntityModel<T> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation(Polemy.MODID, "lare"), "main");

    private final ModelPart body;
    private final ModelPart right_arm;
    private final ModelPart left_arm;
    private final ModelPart left_wing;
    private final ModelPart right_wing;
    private final ModelPart head;

    public LareModel(ModelPart root) {
        this.body = root.getChild("body");
        this.right_arm = root.getChild("right_arm");
        this.left_arm = root.getChild("left_arm");
        this.left_wing = root.getChild("left_wing");
        this.right_wing = root.getChild("right_wing");
        this.head = root.getChild("head");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(0, 10).addBox(-1.5F, 0.0F, -1.0F, 3.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 16).addBox(-1.5F, 1.0F, -1.0F, 3.0F, 5.0F, 2.0F, new CubeDeformation(-0.2F)), PartPose.offset(0.0F, 18.0F, 0.0F));

        partdefinition.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(23, 0).addBox(-1.5F, -0.5F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(-2.0F, 18.5F, 0.0F));

        partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(23, 6).addBox(-0.5F, -0.5F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(2.0F, 18.5F, 0.0F));

        partdefinition.addOrReplaceChild("left_wing", CubeListBuilder.create()
                .texOffs(16, 14).addBox(0.0F, 0.0F, 0.0F, 0.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.5F, 18.0F, 1.0F));

        partdefinition.addOrReplaceChild("right_wing", CubeListBuilder.create()
                .texOffs(16, 14).addBox(0.0F, 0.0F, 0.0F, 0.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(-0.5F, 18.0F, 1.0F));

        partdefinition.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-2.5F, -5.0F, -2.5F, 5.0F, 5.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-0.5F, -1.75F, -3.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 18.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 32, 32);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.head.yRot = netHeadYaw * ((float) Math.PI / 180F);
        this.head.xRot = headPitch * ((float) Math.PI / 180F);

        // Rapid Vex-style wing flutter
        float flap = Mth.cos(ageInTicks * 0.8F) * (float) Math.PI * 0.15F;
        this.right_wing.yRot = 0.45F + flap;
        this.left_wing.yRot = -0.45F - flap;

        // Gentle arm sway
        this.right_arm.xRot = -0.2F + Mth.cos(ageInTicks * 0.15F) * 0.1F;
        this.left_arm.xRot = -0.2F + Mth.sin(ageInTicks * 0.15F) * 0.1F;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        body.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        right_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        left_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        left_wing.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        right_wing.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        head.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
