package cursed.polemy.core.entity.mobs;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

public class WardRune extends Entity {

    private static final EntityDataAccessor<Integer> DATA_CHARGES =
            SynchedEntityData.defineId(WardRune.class, EntityDataSerializers.INT);

    @Nullable
    private UUID hostUUID;
    @Nullable
    private LivingEntity cachedHost;

    public WardRune(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_CHARGES, 3); // 3 hit charges
    }

    public int getCharges() {
        return this.entityData.get(DATA_CHARGES);
    }

    public void setCharges(int charges) {
        this.entityData.set(DATA_CHARGES, charges);
    }

    public void attachTo(LivingEntity host) {
        this.hostUUID = host.getUUID();
        this.cachedHost = host;
        this.setPos(host.getX(), host.getY() + 0.02D, host.getZ());
    }

    @Nullable
    public LivingEntity getHost() {
        if (this.cachedHost != null && this.cachedHost.isAlive()) {
            return this.cachedHost;
        }
        if (this.hostUUID != null && this.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(this.hostUUID);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                this.cachedHost = living;
                return living;
            }
        }
        return null;
    }

    public boolean consumeCharge() {
        int remaining = this.getCharges() - 1;
        this.setCharges(remaining);

        // Ring burst of enchanted particles & ward deflection sound
        this.level().playSound(null, this.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 1.2F, 1.4F);
        if (this.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 16; i++) {
                double angle = (Math.PI * 2.0D * i) / 16.0D;
                serverLevel.sendParticles(ParticleTypes.ENCHANTED_HIT,
                        this.getX() + Math.cos(angle) * 0.8D,
                        this.getY() + 0.15D,
                        this.getZ() + Math.sin(angle) * 0.8D,
                        1, 0.0D, 0.15D, 0.0D, 0.05D);
            }
        }

        if (remaining <= 0) {
            this.level().playSound(null, this.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.0F, 1.1F);
            this.discard();
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            LivingEntity host = this.getHost();
            if (host == null || !host.isAlive() || this.getCharges() <= 0) {
                this.discard();
                return;
            }
            // Keep the ground rune locked directly underneath the host's feet
            this.setPos(host.getX(), host.getY() + 0.02D, host.getZ());
        } else {
            // Subtle rising rune sparks around the perimeter of the circle
            if (this.tickCount % 3 == 0) {
                double angle = this.random.nextDouble() * Math.PI * 2.0D;
                double radius = 0.75D;
                this.level().addParticle(ParticleTypes.ENCHANT,
                        this.getX() + Math.cos(angle) * radius,
                        this.getY() + 0.05D,
                        this.getZ() + Math.sin(angle) * radius,
                        0.0D, 0.04D, 0.0D);
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("HostUUID")) {
            this.hostUUID = tag.getUUID("HostUUID");
        }
        if (tag.contains("Charges")) {
            this.setCharges(tag.getInt("Charges"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.hostUUID != null) {
            tag.putUUID("HostUUID", this.hostUUID);
        }
        tag.putInt("Charges", this.getCharges());
    }

    public boolean isAttachedTo(LivingEntity entity) {
        if (this.cachedHost == entity) {
            return true;
        }
        return this.hostUUID != null && this.hostUUID.equals(entity.getUUID());
    }
}
