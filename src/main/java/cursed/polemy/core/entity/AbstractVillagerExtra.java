package cursed.polemy.core.entity;

import cursed.polemy.core.entity.mobs.Lares;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;

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
    protected void customServerAiStep() {
        super.customServerAiStep();

        // Reset forgiven hit counters once the raid ends or is no longer active
        if (!this.raidForgivenHits.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
            Raid raid = serverLevel.getRaidAt(this.blockPosition());
            if (raid == null || !raid.isActive()) {
                this.raidForgivenHits.clear();
            }
        }
    }

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

}
