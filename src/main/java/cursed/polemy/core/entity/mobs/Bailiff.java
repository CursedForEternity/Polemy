package cursed.polemy.core.entity.mobs;


import cursed.polemy.core.entity.AbstractVillagerExtra;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;

public class Bailiff extends AbstractVillagerExtra {


    public Bailiff(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createSentryAttributes() {
        return createCombatAttributes()
                .add(Attributes.ARMOR, 5.0F);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, false));
        this.goalSelector.addGoal(3, new MoveThroughVillageGoal(this, 0.8D, false, 4, () -> true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, AbstractVillager.class, IronGolem.class, AbstractVillagerExtra.class));

        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Evoker.class, true));

        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Pillager.class, true));

        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Vindicator.class, true));

        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(this, Raider.class, true));

        this.targetSelector.addGoal(6, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                (entity) -> !(entity instanceof Creeper)));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        // Every 10 ticks (0.5s), dynamically switch to a closer Vindicator or closer enemy if one enters melee range
        if (this.tickCount % 10 == 0 && this.getTarget() != null) {
            LivingEntity current = this.getTarget();
            double currentDistSqr = this.distanceToSqr(current);

            // 1. Check for a closer Vindicator first
            List vindicators = this.level().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Vindicator.class,
                    this.getBoundingBox().inflate(16.0D),
                    v -> v.isAlive() && this.hasLineOfSight(v)
            );
            vindicators.sort(Comparator.comparingDouble(e -> this.distanceToSqr((Entity) e)));
            if (!vindicators.isEmpty()) {
                net.minecraft.world.entity.monster.Vindicator closestVindicator = (net.minecraft.world.entity.monster.Vindicator) vindicators.get(0);
                if (closestVindicator != current && (!(current instanceof net.minecraft.world.entity.monster.Vindicator) || this.distanceToSqr(closestVindicator) < currentDistSqr - 2.0D)) {
                    this.setTarget(closestVindicator);
                    return;
                }
            }

            // 2. Otherwise switch if a different enemy is significantly closer (e.g., within 5 blocks while current target is > 10 blocks away)
            if (!(current instanceof net.minecraft.world.entity.monster.Vindicator) && currentDistSqr > 36.0D) {
                List<Monster> nearbyEnemies = this.level().getEntitiesOfClass(
                        Monster.class,
                        this.getBoundingBox().inflate(8.0D),
                        m -> m.isAlive() && !(m instanceof Creeper) && this.hasLineOfSight(m)
                );
                nearbyEnemies.sort(java.util.Comparator.comparingDouble(this::distanceToSqr));
                if (!nearbyEnemies.isEmpty() && this.distanceToSqr(nearbyEnemies.get(0)) < currentDistSqr - 9.0D) {
                    this.setTarget(nearbyEnemies.get(0));
                }
            }
        }
    }


    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && source.getDirectEntity() instanceof AbstractArrow && this.random.nextFloat() < 0.35F) {
            this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 1.1F + this.random.nextFloat() * 0.2F);
            return false;
        }
        return super.hurt(source, amount);
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        this.populateDefaultEquipmentSlots(level.getRandom(), difficulty);
        return data;
    }

    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
    }
}