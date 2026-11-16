package cursed.polemy.core.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractVillagerExtra extends PathfinderMob {

    private static final EntityDataAccessor<Byte> DATA_SPELL_CASTING_ID =
            SynchedEntityData.defineId(AbstractVillagerExtra.class, EntityDataSerializers.BYTE);

    public enum GuardArmPose {
        CROSSED,
        ATTACKING,
        SPELLCASTING,
        BOW_AND_ARROW,
        NEUTRAL
    }

    public enum InvokerSpell {
        NONE(0, 0.0D, 0.0D, 0.0D),
        SUMMON_LARES(1, 0.7D, 0.7D, 0.8D), // Silver-white particles (matches Evoker Vex summon)
        CAST_WARD(2, 0.4D, 0.3D, 0.9D);    // Royal blue-violet particles (matches Evoker Fangs)

        public final int id;
        public final double[] particleSpeed;

        InvokerSpell(int id, double r, double g, double b) {
            this.id = id;
            this.particleSpeed = new double[]{r, g, b};
        }

        public static InvokerSpell byId(int id) {
            for (InvokerSpell spell : values()) {
                if (spell.id == id) return spell;
            }
            return NONE;
        }
    }

    protected AbstractVillagerExtra(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder createCombatAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.ATTACK_DAMAGE, 5.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SPELL_CASTING_ID, (byte) 0);
    }

    public boolean isCastingSpell() {
        return this.entityData.get(DATA_SPELL_CASTING_ID) > 0;
    }

    public void setIsCastingSpell(InvokerSpell spell) {
        this.entityData.set(DATA_SPELL_CASTING_ID, (byte) spell.id);
    }

    public InvokerSpell getCurrentSpell() {
        return InvokerSpell.byId(this.entityData.get(DATA_SPELL_CASTING_ID));
    }

    public GuardArmPose getArmPose() {
        if (this.isCastingSpell()) {
            return GuardArmPose.SPELLCASTING;
        }
        if (this.isAggressive()) {
            if (this.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem) {
                return GuardArmPose.BOW_AND_ARROW;
            }
            return GuardArmPose.ATTACKING;
        }
        return GuardArmPose.CROSSED;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide && this.isCastingSpell()) {
            InvokerSpell spell = this.getCurrentSpell();
            double r = spell.particleSpeed[0];
            double g = spell.particleSpeed[1];
            double b = spell.particleSpeed[2];
            float bodyAngle = this.yBodyRot * ((float) Math.PI / 180F) + Mth.cos((float) this.tickCount * 0.6662F) * 0.25F;
            float cos = Mth.cos(bodyAngle);
            float sin = Mth.sin(bodyAngle);
            this.level().addParticle(ParticleTypes.ENTITY_EFFECT, this.getX() + (double) cos * 0.6D, this.getY() + 1.8D, this.getZ() + (double) sin * 0.6D, r, g, b);
            this.level().addParticle(ParticleTypes.ENTITY_EFFECT, this.getX() - (double) cos * 0.6D, this.getY() + 1.8D, this.getZ() - (double) sin * 0.6D, r, g, b);
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) {
                return false;
            }
            if (this.hasForgivenPlayerInRaid(player)) {
                return false;
            }
        }
        if (target instanceof AbstractVillagerExtra
                || target instanceof net.minecraft.world.entity.npc.AbstractVillager
                || target instanceof net.minecraft.world.entity.animal.IronGolem
                || target instanceof cursed.polemy.core.entity.mobs.Lares) {
            return false;
        }
        return super.canAttack(target);
    }


    @Override
    public void setTarget(@javax.annotation.Nullable LivingEntity target) {
        if (target != null && !this.canAttack(target)) {
            return;
        }
        super.setTarget(target);
    }


    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // Tracks accidental hits per player during an active raid (up to 5 forgiven hits per guard)
    private final Map<UUID, Integer> raidForgivenHits = new HashMap<>();
    private static final int MAX_RAID_FORGIVENESS = 5;

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide()
                && source.getEntity() instanceof Player player
                && !player.isCreative()
                && !player.isSpectator()
                && this.level() instanceof ServerLevel serverLevel) {

            Raid raid = serverLevel.getRaidAt(this.blockPosition());
            if (raid != null && raid.isActive()) {
                UUID playerId = player.getUUID();
                int hitsTaken = this.raidForgivenHits.getOrDefault(playerId, 0) + 1;
                this.raidForgivenHits.put(playerId, hitsTaken);

                if (hitsTaken <= MAX_RAID_FORGIVENESS) {
                    boolean result = super.hurt(source, amount);
                    // Forgive the hit: clear retaliation memory so HurtByTargetGoal does not aggro
                    this.setLastHurtByMob(null);
                    this.setLastHurtByPlayer(null);
                    if (this.getTarget() == player) {
                        this.setTarget(null);
                    }
                    // Visual feedback: splash particles for forgiven hits, angry particle on final (5th) warning
                    serverLevel.sendParticles(
                            hitsTaken == MAX_RAID_FORGIVENESS ? ParticleTypes.ANGRY_VILLAGER : ParticleTypes.SPLASH,
                            this.getX(), this.getY() + this.getBbHeight() + 0.2D, this.getZ(),
                            4, 0.25D, 0.2D, 0.25D, 0.0D
                    );
                    return result;
                }
            }
        }
        return super.hurt(source, amount);
    }

    public boolean hasForgivenPlayerInRaid(Player player) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return false;
        Raid raid = serverLevel.getRaidAt(this.blockPosition());
        if (raid == null || !raid.isActive()) return false;
        return this.raidForgivenHits.getOrDefault(player.getUUID(), 0) <= MAX_RAID_FORGIVENESS;
    }

    @Nullable
    private BlockPos homeVillagePos;

    @Nullable
    public BlockPos getHomeVillagePos() {
        return this.homeVillagePos;
    }

    public void setHomeVillagePos(@Nullable BlockPos pos) {
        this.homeVillagePos = pos;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        this.assignNearestVillageHome();
        return data;
    }

    public void assignNearestVillageHome() {
        if (this.level() instanceof ServerLevel serverLevel) {
            BlockPos current = this.blockPosition();
            BlockPos bellPos = serverLevel.getPoiManager()
                    .findClosest(poi -> poi.is(PoiTypes.MEETING), current, 64, PoiManager.Occupancy.ANY)
                    .orElse(current);
            this.homeVillagePos = bellPos;
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.homeVillagePos != null) {
            tag.put("HomeVillagePos", NbtUtils.writeBlockPos(this.homeVillagePos));
        }
        if (this.morphedVillagerData != null) {
            tag.put("MorphedVillagerData", this.morphedVillagerData);
            tag.putInt("SafeTicksCounter", this.safeTicksCounter);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HomeVillagePos")) {
            this.homeVillagePos = NbtUtils.readBlockPos(tag.getCompound("HomeVillagePos"));
        }
        if (tag.contains("MorphedVillagerData")) {
            this.morphedVillagerData = tag.getCompound("MorphedVillagerData");
            this.safeTicksCounter = tag.getInt("SafeTicksCounter");
        }
    }


    // =========================================================================
    // GOAL: Keeps guards inside village bounds & recalls them if > 64 blocks away
    // =========================================================================
    public static class ReturnToVillageBoundsGoal extends Goal {
        private final AbstractVillagerExtra guard;
        private final double maxCombatLeashSq; // 64 blocks -> 4096.0
        private final double idleReturnDistSq; // 28 blocks -> 784.0
        private double targetX;
        private double targetY;
        private double targetZ;
        private boolean isForcedRecall;

        public ReturnToVillageBoundsGoal(AbstractVillagerExtra guard, double maxCombatRadius, double idleRadius) {
            this.guard = guard;
            this.maxCombatLeashSq = maxCombatRadius * maxCombatRadius;
            this.idleReturnDistSq = idleRadius * idleRadius;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            // Auto-assign a home anchor if spawned via Spawn Egg or command without finalizeSpawn
            if (this.guard.getHomeVillagePos() == null) {
                this.guard.assignNearestVillageHome();
                if (this.guard.getHomeVillagePos() == null) return false;
            }

            BlockPos home = this.guard.getHomeVillagePos();
            double distSq = this.guard.blockPosition().distSqr(home);

            // Case 1: Too far (> 64 blocks) even while chasing an enemy -> abandon chase & sprint home
            if (distSq > this.maxCombatLeashSq) {
                this.isForcedRecall = true;
                this.guard.setTarget(null);
                return this.findStepTowardHome(home);
            }

            // Case 2: No active combat target & drifted outside village bounds (> 28 blocks) -> walk back
            if (this.guard.getTarget() == null && distSq > this.idleReturnDistSq) {
                this.isForcedRecall = false;
                return this.findStepTowardHome(home);
            }

            return false;
        }

        @Override
        public boolean canContinueToUse() {
            BlockPos home = this.guard.getHomeVillagePos();
            if (home == null || this.guard.getNavigation().isDone()) {
                return false;
            }
            // Stop once safely back within 16 blocks of the village center
            return this.guard.blockPosition().distSqr(home) > 256.0D;
        }

        @Override
        public void start() {
            // Sprint back at 1.25x speed if pulled > 64 blocks away; walk at 0.9x speed when idle
            double speed = this.isForcedRecall ? 1.25D : 0.9D;
            this.guard.getNavigation().moveTo(this.targetX, this.targetY, this.targetZ, speed);
        }

        @Override
        public void tick() {
            // Keep clearing targets during forced recall so enemies don't re-bait them out of bounds
            if (this.isForcedRecall && this.guard.getTarget() != null) {
                this.guard.setTarget(null);
            }
        }

        private boolean findStepTowardHome(BlockPos home) {
            Vec3 homeVec = Vec3.atBottomCenterOf(home);
            // Pathfind in 16-block steps toward home (Minecraft's pathfinder struggles with single paths > 32 blocks)
            Vec3 stepPos = DefaultRandomPos.getPosTowards(this.guard, 16, 7, homeVec, (float) Math.PI / 2F);
            if (stepPos == null) {
                stepPos = homeVec;
            }
            this.targetX = stepPos.x;
            this.targetY = stepPos.y;
            this.targetZ = stepPos.z;
            return true;
        }
    }

    @Nullable
    private CompoundTag morphedVillagerData;
    private int safeTicksCounter = 0;
    private static final int REVERT_SAFE_TICKS = 2400; // 2 minutes (120 seconds * 20 ticks)

    public void setMorphedVillagerData(@Nullable CompoundTag villagerTag) {
        this.morphedVillagerData = villagerTag;
        this.safeTicksCounter = 0;
    }

    public boolean isMorphedVillager() {
        return this.morphedVillagerData != null;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (this.level() instanceof ServerLevel serverLevel) {
            // Existing raid forgiveness reset
            if (!this.raidForgivenHits.isEmpty()) {
                Raid raid = serverLevel.getRaidAt(this.blockPosition());
                if (raid == null || !raid.isActive()) {
                    this.raidForgivenHits.clear();
                }
            }

            // Check if this unit is a morphed specialist waiting to revert
            if (this.morphedVillagerData != null) {
                boolean inDanger = this.getTarget() != null
                        || serverLevel.getRaidAt(this.blockPosition()) != null
                        || !serverLevel.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        this.getBoundingBox().inflate(20.0D),
                        m -> m.isAlive() && !(m instanceof net.minecraft.world.entity.monster.Creeper)).isEmpty();

                if (inDanger) {
                    this.safeTicksCounter = 0;
                } else {
                    this.safeTicksCounter++;
                    if (this.safeTicksCounter >= REVERT_SAFE_TICKS) {
                        this.revertBackToVillager(serverLevel);
                    }
                }
            }
        }
    }

    private void revertBackToVillager(ServerLevel level) {
        net.minecraft.world.entity.npc.Villager restored = EntityType.VILLAGER.create(level);
        if (restored != null && this.morphedVillagerData != null) {
            restored.load(this.morphedVillagerData);
            restored.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
            restored.setHealth(restored.getMaxHealth());
            level.addFreshEntity(restored);
            level.playSound(null, this.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    this.getX(), this.getY() + 1.5D, this.getZ(), 8, 0.3D, 0.4D, 0.3D, 0.0D);
            this.discard();
        }
    }

}
