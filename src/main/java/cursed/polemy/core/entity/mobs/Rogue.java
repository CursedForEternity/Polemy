package cursed.polemy.core.entity.mobs;

import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.core.events.WitchNeutralityHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public class Rogue extends AbstractVillagerExtra {

    @Nullable
    private CompoundTag morphedTraderData;
    private int traderSafeTicks = 0;
    private int invisCooldown = 0;
    private static final int TRADER_REVERT_TICKS = 1200; // 1 minute (60s * 20 ticks)

    public Rogue(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createRogueAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.42D) // Faster than standard guards for flanking
                .add(Attributes.ATTACK_DAMAGE, 5.0D)
                .add(Attributes.FOLLOW_RANGE, 36.0D);
    }

    public void setMorphedTraderData(@Nullable CompoundTag traderNbt) {
        this.morphedTraderData = traderNbt;
        this.traderSafeTicks = 0;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        // Start with empty hands so arms stay folded until a target is engaged
        this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        this.drinkInvisibilityPotion();
        return data;
    }


    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new ReturnToVillageBoundsGoal(this, 64.0D, 28.0D));
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        // Flee away if health drops critical (< 30% HP) and invisible
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Monster.class, 64.0F, 1.15D, 1.35D) {
            @Override
            public boolean canUse() {
                return Rogue.this.getHealth() < Rogue.this.getMaxHealth() * 0.30F && super.canUse();
            }
        });
        // Flanking backstab melee attack
        this.goalSelector.addGoal(4, new BackstabMeleeGoal(this, 1.25D, true));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, AbstractVillager.class, IronGolem.class, AbstractVillagerExtra.class));
        // Priority 2: Assassinate Evokers and Witches first during raids
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Evoker.class, false));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Witch.class, false));
        // Priority 3: Other Raiders & Monsters
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Raider.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                e -> !(e instanceof Creeper)));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (this.invisCooldown > 0) {
            this.invisCooldown--;
        }

        // Re-drink Invisibility Potion when in combat and invisibility expires
        if (this.getTarget() != null && !this.hasEffect(MobEffects.INVISIBILITY) && this.invisCooldown <= 0) {
            this.drinkInvisibilityPotion();
        }

        // Hide held sword in offhand/empty visual while invisible so mobs/players see true stealth
        if (this.hasEffect(MobEffects.INVISIBILITY)) {
            if (!this.getMainHandItem().isEmpty()) {
                this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        } else if (this.getMainHandItem().isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        }

        // Keep arms folded (empty hand) when out of combat or invisible; draw iron sword when attacking!
        boolean shouldDrawSword = this.getTarget() != null && !this.hasEffect(MobEffects.INVISIBILITY);
        if (shouldDrawSword) {
            if (this.getMainHandItem().isEmpty()) {
                this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            }
        } else if (!this.getMainHandItem().isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        // Revert back to Wandering Trader after 1 minute with no danger within 64 blocks
        if (this.morphedTraderData != null && this.level() instanceof ServerLevel serverLevel) {
            boolean dangerWithin64 = this.getTarget() != null
                    || serverLevel.getRaidAt(this.blockPosition()) != null
                    || !serverLevel.getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(64.0D),
                            m -> m.isAlive() && !(m instanceof Creeper)).isEmpty();

            if (dangerWithin64) {
                this.traderSafeTicks = 0;
            } else {
                this.traderSafeTicks++;
                if (this.traderSafeTicks >= TRADER_REVERT_TICKS) {
                    this.revertBackToTrader(serverLevel);
                }
            }
        }
    }

    public void drinkInvisibilityPotion() {
        this.invisCooldown = 200; // 10-second cooldown between potions
        this.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 300, 0, false, false)); // 15s stealth
        this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1, false, false)); // Speed II burst

        ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.INVISIBILITY);
        this.setItemInHand(InteractionHand.OFF_HAND, potion);
        this.playSound(SoundEvents.GENERIC_DRINK, 0.9F, 1.1F);

        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY() + 1.0D, this.getZ(),
                    10, 0.25D, 0.4D, 0.25D, 0.02D);
        }
        this.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
    }

    private void revertBackToTrader(ServerLevel level) {
        WanderingTrader trader = EntityType.WANDERING_TRADER.create(level);
        if (trader != null && this.morphedTraderData != null) {
            trader.load(this.morphedTraderData);
            trader.removeAllEffects();
            trader.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
            trader.setHealth(trader.getMaxHealth());
            level.addFreshEntity(trader);

            level.playSound(null, this.blockPosition(), SoundEvents.WANDERING_TRADER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getY() + 1.5D, this.getZ(),
                    8, 0.3D, 0.4D, 0.3D, 0.0D);
            this.discard();
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.morphedTraderData != null) {
            tag.put("MorphedTraderData", this.morphedTraderData);
            tag.putInt("TraderSafeTicks", this.traderSafeTicks);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("MorphedTraderData")) {
            this.morphedTraderData = tag.getCompound("MorphedTraderData");
            this.traderSafeTicks = tag.getInt("TraderSafeTicks");
        }
    }

    private static class BackstabMeleeGoal extends MeleeAttackGoal {
        private final Rogue rogue;

        public BackstabMeleeGoal(Rogue rogue, double speedModifier, boolean followingTargetEvenIfNotSeen) {
            super(rogue, speedModifier, followingTargetEvenIfNotSeen);
            this.rogue = rogue;
        }

        @Override
        public void tick() {
            LivingEntity target = this.rogue.getTarget();
            if (target != null && target.isAlive()) {
                // Pathfind to a point 1.8 blocks BEHIND the target's facing direction
                Vec3 targetLook = target.getLookAngle();
                Vec3 behindPos = target.position().subtract(targetLook.x * 1.8D, 0.0D, targetLook.z * 1.8D);

                if (this.rogue.distanceToSqr(behindPos) > 2.25D) {
                    this.rogue.getNavigation().moveTo(behindPos.x, target.getY(), behindPos.z, 1.3D);
                }
            }
            super.tick();
        }

        @Override
        protected void checkAndPerformAttack(LivingEntity target, double distToEnemySqr) {
            double reach = this.getAttackReachSqr(target);
            if (distToEnemySqr <= reach && this.getTicksUntilNextAttack() <= 0) {
                this.resetAttackCooldown();
                this.rogue.swing(InteractionHand.MAIN_HAND);
// Check if Rogue is behind the target (dot product > 0.35 means striking their back)
                Vec3 toTarget = target.position().subtract(this.rogue.position()).normalize();
                Vec3 targetFacing = target.getLookAngle().normalize();
                boolean isBackstab = toTarget.x * targetFacing.x + toTarget.z * targetFacing.z > 0.35D;
                float baseDamage = (float) this.rogue.getAttributeValue(Attributes.ATTACK_DAMAGE);
                float finalDamage = isBackstab ? baseDamage * 2.5F : baseDamage;
// Briefly reveal on strike, then re-cloak if potion is off cooldown
                this.rogue.removeEffect(MobEffects.INVISIBILITY);
                target.hurt(this.rogue.damageSources().mobAttack(this.rogue), finalDamage);
                if (isBackstab && this.rogue.level() instanceof ServerLevel serverLevel) {
                    serverLevel.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT,
                            SoundSource.NEUTRAL, 1.2F, 1.2F);
                    serverLevel.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.2D, target.getZ(),
                            12, 0.3D, 0.3D, 0.3D, 0.15D);
                }
            }
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Witch witch) {
            if (WitchNeutralityHandler.isVillageDefenderWitch(witch)) {
                return false;
            }
            boolean hostile = WitchNeutralityHandler.isWitchInRaid(witch)
                    || witch.getTarget() != null
                    || this.getLastHurtByMob() == witch;
            if (!hostile) {
                return false;
            }
        }
        return super.canAttack(target);
    }
}