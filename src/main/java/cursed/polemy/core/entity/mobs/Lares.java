package cursed.polemy.core.entity.mobs;

import cursed.polemy.core.entity.AbstractVillagerExtra;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class Lares extends PathfinderMob {

    @Nullable
    private LivingEntity targetAlly;
    private int healCooldown = 40;
    private int lifeTicks = 600;

    public Lares(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new VexLikeMoveControl(this);
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 14.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D);
    }

    public boolean isValidAlly(LivingEntity entity) {
        if (!entity.isAlive() || entity instanceof Lares || entity instanceof Enemy) {
            return false;
        }
        return entity instanceof AbstractVillagerExtra
                || entity instanceof AbstractVillager
                || entity instanceof IronGolem
                || (entity instanceof Player player && !player.isCreative() && !player.isSpectator());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FollowLowestHpAllyGoal());
        this.goalSelector.addGoal(2, new RandomHoverGoal());
    }

    @Override
    public void tick() {
        this.noPhysics = true;
        super.tick();
        this.noPhysics = true;
        this.setNoGravity(true);

        if (!this.level().isClientSide) {
            if (this.lifeTicks > 0 && --this.lifeTicks <= 0) {
                this.discard();
                return;
            }
            if (--this.healCooldown <= 0) {
                this.healCooldown = 40;
                this.pulsePassiveHeal();
            }
        }
    }

    private void pulsePassiveHeal() {
        List<LivingEntity> allies = this.level().getEntitiesOfClass(
                LivingEntity.class,
                this.getBoundingBox().inflate(6.0D),
                this::isValidAlly
        );

        boolean healedAnyone = false;
        for (LivingEntity ally : allies) {
            if (ally.getHealth() < ally.getMaxHealth()) {
                ally.heal(2.0F);
                healedAnyone = true;
                if (this.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(
                            ParticleTypes.HEART,
                            ally.getX(),
                            ally.getY() + ally.getBbHeight() + 0.3D,
                            ally.getZ(),
                            1,
                            0.2D, 0.2D, 0.2D,
                            0.0D
                    );
                }
            }
        }

        if (healedAnyone && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    this.getX(),
                    this.getY() + 0.5D,
                    this.getZ(),
                    4,
                    0.3D, 0.3D, 0.3D,
                    0.0D
            );
        }
    }

    private class FollowLowestHpAllyGoal extends Goal {
        private static final int MAX_LARES_PER_ALLY = 3;
        private int recheckTicks;

        public FollowLowestHpAllyGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            this.findLowestHpAlly();
            return Lares.this.targetAlly != null;
        }

        @Override
        public boolean canContinueToUse() {
            return Lares.this.targetAlly != null
                    && Lares.this.targetAlly.isAlive()
                    && Lares.this.distanceToSqr(Lares.this.targetAlly) < 576.0D;
        }

        @Override
        public void tick() {
            if (--this.recheckTicks <= 0) {
                this.recheckTicks = 10;
                this.findLowestHpAlly();
            }

            if (Lares.this.targetAlly != null) {
                LivingEntity ally = Lares.this.targetAlly;
                // Offset each Lare by 120 degrees so up to 3 Lares space out evenly around the ally
                double phaseOffset = (Lares.this.getId() % 3) * ((Math.PI * 2.0D) / 3.0D);
                double orbitAngle = (Lares.this.tickCount * 0.08D) + phaseOffset;

                double hoverX = ally.getX() + Math.cos(orbitAngle) * 1.15D;
                double hoverY = ally.getY() + ally.getBbHeight() + 0.7D;
                double hoverZ = ally.getZ() + Math.sin(orbitAngle) * 1.15D;

                Lares.this.moveControl.setWantedPosition(hoverX, hoverY, hoverZ, 1.35D);
            }
        }

        private void findLowestHpAlly() {
            List<LivingEntity> allies = Lares.this.level().getEntitiesOfClass(
                    LivingEntity.class,
                    Lares.this.getBoundingBox().inflate(24.0D),
                    Lares.this::isValidAlly
            );

            if (allies.isEmpty()) {
                Lares.this.targetAlly = null;
                return;
            }

            // Sort from the lowest current HP to highest, with distance as tie-breaker
            allies.sort(Comparator
                    .comparingDouble(LivingEntity::getHealth)
                    .thenComparingDouble(Lares.this::distanceToSqr));

            // Pick the lowest-HP ally that has fewer than 3 OTHER Lares assigned
            for (LivingEntity candidate : allies) {
                if (this.countOtherLaresTargeting(candidate) < MAX_LARES_PER_ALLY) {
                    Lares.this.targetAlly = candidate;
                    return;
                }
            }

            // Fallback: if all valid allies already have 3 Lares, assist the lowest-HP ally
            Lares.this.targetAlly = allies.get(0);
        }

        private long countOtherLaresTargeting(LivingEntity candidate) {
            List<Lares> nearbyLares = Lares.this.level().getEntitiesOfClass(
                    Lares.class,
                    Lares.this.getBoundingBox().inflate(28.0D),
                    other -> other.isAlive() && other != Lares.this
            );
            return nearbyLares.stream()
                    .filter(other -> other.targetAlly == candidate)
                    .count();
        }
    }


    private class RandomHoverGoal extends Goal {
        public RandomHoverGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return !Lares.this.getMoveControl().hasWanted();
        }

        @Override
        public void tick() {
            BlockPos base = Lares.this.blockPosition();
            for (int i = 0; i < 3; ++i) {
                BlockPos target = base.offset(
                        Lares.this.random.nextInt(9) - 4,
                        Lares.this.random.nextInt(5) - 2,
                        Lares.this.random.nextInt(9) - 4
                );
                if (Lares.this.level().isEmptyBlock(target)) {
                    Lares.this.moveControl.setWantedPosition(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 0.8D);
                    break;
                }
            }
        }
    }

    private static class VexLikeMoveControl extends MoveControl {
        public VexLikeMoveControl(Lares lares) {
            super(lares);
        }

        @Override
        public void tick() {
            if (this.operation == Operation.MOVE_TO) {
                Vec3 vec = new Vec3(this.wantedX - this.mob.getX(), this.wantedY - this.mob.getY(), this.wantedZ - this.mob.getZ());
                double dist = vec.length();
                if (dist < 0.25D) {
                    this.operation = Operation.WAIT;
                    this.mob.setDeltaMovement(this.mob.getDeltaMovement().scale(0.5D));
                } else {
                    this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(vec.scale(this.speedModifier * 0.06D / dist)));
                    Vec3 movement = this.mob.getDeltaMovement();
                    float yaw = -((float) Mth.atan2(movement.x, movement.z)) * (180F / (float) Math.PI);
                    this.mob.setYRot(yaw);
                    this.mob.yBodyRot = yaw;
                    this.mob.yHeadRot = yaw;
                }
            }
        }
    }
}
