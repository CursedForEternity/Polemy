package cursed.polemy.core.entity.mobs;

import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class Invoker extends AbstractVillagerExtra {

    protected int spellCastingTickCount;
    private int nextSpellTickCount;

    public Invoker(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CastingSpellGoal());
        this.goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Monster.class, 8.0F, 0.8D, 1.1D));
        this.goalSelector.addGoal(3, new ReturnToVillageBoundsGoal(this, 64.0D, 28.0D));
        this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));

        this.goalSelector.addGoal(4, new SummonLaresSpellGoal());
        this.goalSelector.addGoal(5, new CastWardSpellGoal());

        this.goalSelector.addGoal(6, new MoveThroughVillageGoal(this, 0.8D, false, 4, () -> true));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, AbstractVillager.class, IronGolem.class, AbstractVillagerExtra.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Raider.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, true));
    }

    // Rally up to 2 Bailiffs and 4 Sentries whenever the Invoker is attacked
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (!this.level().isClientSide() && source.getEntity() instanceof LivingEntity attacker) {
            if (this.isValidEnemy(attacker)) {
                this.rallyNearbyGuards(attacker);
            }
        }
        return result;
    }

    protected SoundEvent getCastingSoundEvent() {
        return SoundEvents.EVOKER_CAST_SPELL;
    }

    private class CastingSpellGoal extends Goal {
        public CastingSpellGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return Invoker.this.spellCastingTickCount > 0;
        }

        @Override
        public void start() {
            Invoker.this.getNavigation().stop();
        }

        @Override
        public void stop() {
            Invoker.this.spellCastingTickCount = 0;
            Invoker.this.setIsCastingSpell(InvokerSpell.NONE);
        }

        @Override
        public void tick() {
            if (Invoker.this.getTarget() != null) {
                Invoker.this.getLookControl().setLookAt(Invoker.this.getTarget(), (float) Invoker.this.getMaxHeadYRot(), (float) Invoker.this.getMaxHeadXRot());
            }
        }
    }


    private abstract class UseSpellGoal extends Goal {
        protected int attackWarmupDelay;
        protected int nextAttackTickCount;

        @Override
        public boolean canUse() {
            LivingEntity target = Invoker.this.getTarget();
            if (target == null || !target.isAlive() || Invoker.this.isCastingSpell()) {
                return false;
            }
            return Invoker.this.tickCount >= this.nextAttackTickCount && Invoker.this.tickCount >= Invoker.this.nextSpellTickCount;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = Invoker.this.getTarget();
            return target != null && target.isAlive() && this.attackWarmupDelay > 0;
        }

        @Override
        public void start() {
            this.attackWarmupDelay = this.adjustedTickDelay(this.getCastWarmupTime());
            Invoker.this.spellCastingTickCount = this.getCastingTime();
            this.nextAttackTickCount = Invoker.this.tickCount + this.getCastingInterval();
            Invoker.this.nextSpellTickCount = Invoker.this.tickCount + 20;

            SoundEvent prepareSound = this.getSpellPrepareSound();
            if (prepareSound != null) {
                Invoker.this.playSound(prepareSound, 1.0F, 1.0F);
            }
            Invoker.this.setIsCastingSpell(this.getSpell());
        }

        @Override
        public void tick() {
            --this.attackWarmupDelay;
            if (this.attackWarmupDelay == 0) {
                this.performSpellCasting();
                Invoker.this.playSound(Invoker.this.getCastingSoundEvent(), 1.0F, 1.0F);
            }
        }

        @Override
        public void stop() {
            this.attackWarmupDelay = 0;
            Invoker.this.spellCastingTickCount = 0;
            Invoker.this.setIsCastingSpell(InvokerSpell.NONE);
        }


        protected abstract void performSpellCasting();
        protected abstract int getCastWarmupTime();
        protected abstract int getCastingTime();
        protected abstract int getCastingInterval();
        @Nullable
        protected abstract SoundEvent getSpellPrepareSound();
        protected abstract InvokerSpell getSpell();
    }

    private void rallyNearbyGuards(LivingEntity attacker) {
        List<Bailiff> bailiffs = this.level().getEntitiesOfClass(
                Bailiff.class,
                this.getBoundingBox().inflate(24.0D),
                guard -> guard.isAlive() && guard.getTarget() != attacker
        );
        bailiffs.sort(Comparator.comparingDouble(this::distanceToSqr));
        int bailiffLimit = Math.min(2, bailiffs.size());
        for (int i = 0; i < bailiffLimit; i++) {
            bailiffs.get(i).setTarget(attacker);
        }

        List<Sentry> sentries = this.level().getEntitiesOfClass(
                Sentry.class,
                this.getBoundingBox().inflate(24.0D),
                guard -> guard.isAlive() && guard.getTarget() != attacker
        );
        sentries.sort(Comparator.comparingDouble(this::distanceToSqr));
        int sentryLimit = Math.min(4, sentries.size());
        for (int i = 0; i < sentryLimit; i++) {
            sentries.get(i).setTarget(attacker);
        }
    }

    private boolean isValidEnemy(LivingEntity entity) {
        if (!entity.isAlive()) return false;
        return !(entity instanceof AbstractVillagerExtra)
                && !(entity instanceof AbstractVillager)
                && !(entity instanceof IronGolem)
                && !(entity instanceof Lares);
    }

    private boolean isFriendlyUnit(LivingEntity entity) {
        if (!entity.isAlive() || entity instanceof Lares || entity instanceof Enemy) {
            return false;
        }
        if (entity == this.getTarget() || entity == this.getLastHurtByMob()) {
            return false;
        }
        if (entity instanceof Mob mob && mob.getTarget() != null && this.isFriendlyUnitRaw(mob.getTarget())) {
            return false;
        }
        return this.isFriendlyUnitRaw(entity);
    }

    private boolean isFriendlyUnitRaw(LivingEntity entity) {
        return entity instanceof AbstractVillagerExtra
                || entity instanceof AbstractVillager
                || entity instanceof IronGolem
                || (entity instanceof Player player && !player.isCreative() && !player.isSpectator() && this.getLastHurtByMob() != player);
    }


    private class SummonLaresSpellGoal extends UseSpellGoal {
        @Override
        public boolean canUse() {
            if (!super.canUse()) {
                return false;
            }
            int activeLares = Invoker.this.level().getEntitiesOfClass(Lares.class, Invoker.this.getBoundingBox().inflate(16.0D)).size();
            return activeLares < 3;
        }

        @Override
        protected int getCastWarmupTime() {
            return 40;
        }

        @Override
        protected int getCastingTime() {
            return 100;
        }

        @Override
        protected int getCastingInterval() {
            return 340;
        }

        @Override
        protected void performSpellCasting() {
            if (!(Invoker.this.level() instanceof ServerLevel serverLevel)) return;

            int count = 2 + Invoker.this.random.nextInt(2);
            for (int i = 0; i < count; ++i) {
                BlockPos spawnPos = Invoker.this.blockPosition().offset(
                        -2 + Invoker.this.random.nextInt(5),
                        1,
                        -2 + Invoker.this.random.nextInt(5)
                );
                Lares lare = MobsPolemy.LARES.get().create(serverLevel);
                if (lare != null) {
                    lare.moveTo(spawnPos, 0.0F, 0.0F);
                    lare.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(spawnPos), MobSpawnType.MOB_SUMMONED, null, null);
                    serverLevel.addFreshEntity(lare);
                }
            }
        }
        @Override
        protected SoundEvent getSpellPrepareSound() {
            return SoundEvents.EVOKER_PREPARE_SUMMON;
        }
        @Override
        protected InvokerSpell getSpell() {
            return InvokerSpell.SUMMON_LARES;
        }
    }
    private class CastWardSpellGoal extends UseSpellGoal {
        @Override
        public boolean canUse() {
            if (!super.canUse()) {
                return false;
            }
            return !this.findUnwardedAllies().isEmpty();
        }
        @Override
        protected int getCastWarmupTime() {
            return 40;
        }
        @Override
        protected int getCastingTime() {
            return 40;
        }
        @Override
        protected int getCastingInterval() {
            return 100;
        }
        @Override
        protected void performSpellCasting() {
            if (!(Invoker.this.level() instanceof ServerLevel serverLevel)) return;
            List candidates = this.findUnwardedAllies();
            int wardsToCast = Math.min(3, candidates.size());
            for (int i = 0; i < wardsToCast; i++) {
                LivingEntity ally = (LivingEntity) candidates.get(i);
                WardRune rune = MobsPolemy.WARD_RUNE.get().create(serverLevel);
                if (rune != null) {
                    rune.attachTo(ally);
                    serverLevel.addFreshEntity(rune);
                }
            }
        }
        private List findUnwardedAllies() {
            List allies = Invoker.this.level().getEntitiesOfClass(
                    LivingEntity.class,
                    Invoker.this.getBoundingBox().inflate(16.0D),
                    ally -> Invoker.this.isFriendlyUnit(ally) && !this.hasActiveWard(ally)
            );
            allies.sort(Comparator
                    .comparingInt((LivingEntity e) -> e instanceof Bailiff ? 0 : 1)
                    .thenComparingDouble(e -> e.getHealth() / e.getMaxHealth()));
            return allies;
        }
        private boolean hasActiveWard(LivingEntity ally) {
            return !Invoker.this.level().getEntitiesOfClass(
                    WardRune.class,
                    ally.getBoundingBox().inflate(2.0D, 3.0D, 2.0D),
                    rune -> rune.isAlive() && rune.getCharges() > 0 && rune.isAttachedTo(ally)
            ).isEmpty();
        }
        @Override
        protected SoundEvent getSpellPrepareSound() {
            return SoundEvents.EVOKER_PREPARE_ATTACK;
        }
        @Override
        protected InvokerSpell getSpell() {
            return InvokerSpell.CAST_WARD;
        }
    }
}