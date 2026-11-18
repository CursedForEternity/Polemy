package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WitchNeutralityHandler {

    // Maps Village Center -> Set of Player UUIDs who recently harmed villagers there
    private static final Map<BlockPos, Set<UUID>> VILLAGE_OFFENDERS = new HashMap<>();

    public static boolean isWitchInRaid(Witch witch) {
        if (witch.hasActiveRaid()) {
            return true;
        }
        if (witch.level() instanceof ServerLevel serverLevel) {
            var raid = serverLevel.getRaidAt(witch.blockPosition());
            return raid != null && raid.isActive();
        }
        return false;
    }

    public static boolean isVillageDefenderWitch(Witch witch) {
        return witch.getPersistentData().getBoolean("VillageDefender");
    }

    public static boolean isSapientBeing(LivingEntity entity) {
        return entity instanceof Player
                || entity instanceof AbstractVillager
                || entity instanceof AbstractVillagerExtra
                || entity instanceof IronGolem
                || entity instanceof AbstractIllager;
    }

    public static void convertToVillageDefender(ServerLevel level, Witch witch) {
        if (isVillageDefenderWitch(witch)) return;

        witch.getPersistentData().putBoolean("VillageDefender", true);
        witch.setTarget(null);

        // Remove from Raid boss-bar count so players can win the raid without killing allied witches
        if (witch.getCurrentRaid() != null) {
            witch.getCurrentRaid().removeFromRaid(witch, true);
        }

        witch.playSound(SoundEvents.WITCH_CELEBRATE, 1.1F, 1.1F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                witch.getX(), witch.getY() + 2.0D, witch.getZ(),
                8, 0.3D, 0.3D, 0.3D, 0.0D);
    }

    // =========================================================================
    // 1. RECORD PLAYER OFFENSES WHEN THEY HURT VILLAGERS OR GUARDS
    // =========================================================================
    @SubscribeEvent
    public static void onVillagerHurtByPlayer(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Villager) && !(event.getEntity() instanceof AbstractVillagerExtra)) {
            return;
        }
        if (event.getSource().getEntity() instanceof Player player && !player.isCreative() && !player.isSpectator()) {
            BlockPos center = VillagerLifeHandler.getVillageCenter(level, event.getEntity().blockPosition());
            VILLAGE_OFFENDERS.computeIfAbsent(center, k -> new HashSet<>()).add(player.getUUID());
        }
    }

    // =========================================================================
    // 2. RAID START & RAID-WITCH DEFECTION SCANNER
    // =========================================================================
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        // Check every 10 ticks (0.5 seconds) so local witches flip to defenders before fighting villagers
        if (level.getGameTime() % 10L != 0L) {
            return;
        }

        for (Witch witch : level.getEntities(EntityType.WITCH, LivingEntity::isAlive)) {
            var raid = level.getRaidAt(witch.blockPosition());
            if (raid == null || !raid.isActive()) {
                continue;
            }

            if (!isVillageDefenderWitch(witch)) {
                // 1. Local village witch -> immediately joins village defense
                if (witch.getPersistentData().getBoolean("LocalVillageWitch")
                        && witch.blockPosition().distSqr(raid.getCenter()) <= 3600.0D) {
                    convertToVillageDefender(level, witch);
                    continue;
                }

                // 2. Raid-wave witch spots an existing Village Defender Witch -> switches sides!
                List<Witch> nearbyDefenders = level.getEntitiesOfClass(
                        Witch.class,
                        witch.getBoundingBox().inflate(24.0D),
                        other -> other.isAlive() && other != witch && isVillageDefenderWitch(other)
                );
                for (Witch defender : nearbyDefenders) {
                    if (witch.hasLineOfSight(defender)) {
                        convertToVillageDefender(level, witch);
                        break;
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. MUTUAL NEUTRALITY & VILLAGE DEFENDER PROTECTION
    // =========================================================================
    @SubscribeEvent
    public static void onTargetChange(LivingChangeTargetEvent event) {
        LivingEntity attacker = event.getEntity();
        LivingEntity newTarget = event.getNewTarget();
        if (newTarget == null || attacker.level().isClientSide()) {
            return;
        }

        // Village Defender Witches never target Players, Villagers, Guards, Golems, or other Defender Witches
        if (attacker instanceof Witch witch && isVillageDefenderWitch(witch)) {
            if (isSapientBeing(newTarget) && !(newTarget instanceof Raider)) {
                event.setCanceled(true);
                return;
            }
            if (newTarget instanceof Witch otherWitch && isVillageDefenderWitch(otherWitch)) {
                event.setCanceled(true);
                return;
            }
        }

        // Guards and Iron Golems never attack a Village Defender Witch
        if (newTarget instanceof Witch witch && isVillageDefenderWitch(witch)) {
            if (attacker instanceof AbstractVillagerExtra || attacker instanceof IronGolem) {
                event.setCanceled(true);
                return;
            }
        }

        // Case A: Non-defender Witch trying to target a sapient being outside a raid
        if (attacker instanceof Witch witch && !isWitchInRaid(witch) && !isVillageDefenderWitch(witch)) {
            if (isSapientBeing(newTarget) && witch.getLastHurtByMob() != newTarget) {
                event.setCanceled(true);
                return;
            }
        }

        // Case B: Illager, Guard, or Iron Golem trying to target a peaceful Witch outside a raid
        if (newTarget instanceof Witch witch && !isWitchInRaid(witch) && !isVillageDefenderWitch(witch)) {
            boolean witchIsHostile = witch.getTarget() != null && isSapientBeing(witch.getTarget());
            boolean witchHurtAttacker = attacker.getLastHurtByMob() == witch;

            if (!witchIsHostile && !witchHurtAttacker) {
                if (attacker instanceof AbstractIllager
                        || attacker instanceof AbstractVillagerExtra
                        || attacker instanceof IronGolem) {
                    event.setCanceled(true);
                }
            }
        }
    }

    // =========================================================================
    // 4. INJECT GOALS INTO VILLAGERS, CLERICS, AND WITCHES
    // =========================================================================
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (event.getEntity() instanceof Villager villager) {
            villager.goalSelector.addGoal(4, new ClericGossipWithWitchGoal(villager));
            villager.goalSelector.addGoal(5, new VillagerTradeWithWitchGoal(villager));
        } else if (event.getEntity() instanceof Witch witch) {
            witch.goalSelector.addGoal(1, new VillageDefenderWitchGoal(witch));
            witch.goalSelector.addGoal(1, new WitchHexOffenderGoal(witch));

            if (event.getLevel() instanceof ServerLevel serverLevel) {
                var raid = serverLevel.getRaidAt(witch.blockPosition());
                if ((raid == null || !raid.isActive()) && !witch.hasActiveRaid()) {
                    witch.getPersistentData().putBoolean("LocalVillageWitch", true);
                }
            }
        }
    }
    // =========================================================================
// GOAL A: CLERICS TRADE BREWING SUPPLIES & GOSSIP ABOUT OFFENDING PLAYERS
// =========================================================================
    private static class ClericGossipWithWitchGoal extends Goal {
        private final Villager cleric;
        private Witch targetWitch;
        private int gossipTicks;
        private int cooldown = 240;
        public ClericGossipWithWitchGoal(Villager villager) {
            this.cleric = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override
        public boolean canUse() {
            if (this.cleric.getVillagerData().getProfession() != VillagerProfession.CLERIC || this.cleric.isBaby()) {
                return false;
            }
            if (!(this.cleric.level() instanceof ServerLevel level) || level.getRaidAt(this.cleric.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;
            List witches = level.getEntitiesOfClass(
                    Witch.class,
                    this.cleric.getBoundingBox().inflate(18.0D),
                    w -> w.isAlive() && !isWitchInRaid(w) && w.getTarget() == null && !w.getPersistentData().hasUUID("HexTargetPlayer")
            );
            if (witches.isEmpty()) return false;
            this.targetWitch = (Witch) witches.get(this.cleric.getRandom().nextInt(witches.size()));
            return true;
        }
        @Override
        public void start() {
            this.cooldown = 900;
            this.gossipTicks = 60;
            this.cleric.getNavigation().moveTo(this.targetWitch, 0.6D);
        }
        @Override
        public boolean canContinueToUse() {
            return this.targetWitch != null
                    && this.targetWitch.isAlive()
                    && !isWitchInRaid(this.targetWitch)
                    && this.gossipTicks > 0;
        }
        @Override
        public void tick() {
            if (this.targetWitch == null || !(this.cleric.level() instanceof ServerLevel level)) return;
            if (this.cleric.distanceToSqr(this.targetWitch) > 5.0D) {
                this.cleric.getNavigation().moveTo(this.targetWitch, 0.6D);
                return;
            }
            this.cleric.getNavigation().stop();
            this.cleric.getLookControl().setLookAt(this.targetWitch);
            this.targetWitch.getLookControl().setLookAt(this.cleric);
            this.gossipTicks--;
            if (this.gossipTicks == 40) {
                this.cleric.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHER_WART));
                this.targetWitch.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POTION));
                this.cleric.playSound(SoundEvents.VILLAGER_AMBIENT, 1.0F, 1.0F);
                this.targetWitch.playSound(SoundEvents.WITCH_AMBIENT, 0.9F, 1.05F);
            }
            if (this.gossipTicks <= 0) {
                this.cleric.getInventory().addItem(new ItemStack(Items.REDSTONE, 2));
                this.cleric.playSound(SoundEvents.VILLAGER_TRADE, 1.0F, 1.0F);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.cleric.getX(), this.cleric.getY() + 2.0D, this.cleric.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.0D);
                BlockPos center = VillagerLifeHandler.getVillageCenter(level, this.cleric.blockPosition());
                Set offenders = VILLAGE_OFFENDERS.get(center);
                if (offenders != null && !offenders.isEmpty()) {
                    for (Player nearbyPlayer : level.getEntitiesOfClass(Player.class, this.cleric.getBoundingBox().inflate(28.0D))) {
                        if (offenders.contains(nearbyPlayer.getUUID()) && !nearbyPlayer.isCreative() && !nearbyPlayer.isSpectator()) {
                            this.targetWitch.getPersistentData().putUUID("HexTargetPlayer", nearbyPlayer.getUUID());
                            offenders.remove(nearbyPlayer.getUUID());
                            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, this.cleric.getX(), this.cleric.getY() + 2.0D, this.cleric.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.0D);
                            level.sendParticles(ParticleTypes.WITCH, this.targetWitch.getX(), this.targetWitch.getY() + 2.0D, this.targetWitch.getZ(), 6, 0.25D, 0.2D, 0.25D, 0.0D);
                            break;
                        }
                    }
                }
            }
        }
        @Override
        public void stop() {
            this.cleric.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (this.targetWitch != null) {
                this.targetWitch.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            this.targetWitch = null;
        }
    }
    // =========================================================================
// GOAL B: WITCH WALKS TO GOSSIPED PLAYER -> THROWS WEAKNESS + SLOWNESS -> RETURNS
// =========================================================================
    private static class WitchHexOffenderGoal extends Goal {
        private final Witch witch;
        private Player targetPlayer;
        private BlockPos returnVillagePos;
        private int throwStage = 0;
        private int stageDelayTicks = 0;
        public WitchHexOffenderGoal(Witch witch) {
            this.witch = witch;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override
        public boolean canUse() {
            if (!(this.witch.level() instanceof ServerLevel level) || isWitchInRaid(this.witch)) {
                return false;
            }
            CompoundTag tag = this.witch.getPersistentData();
            if (!tag.hasUUID("HexTargetPlayer")) {
                return false;
            }
            Player player = level.getPlayerByUUID(tag.getUUID("HexTargetPlayer"));
            if (player == null || !player.isAlive() || player.isCreative() || player.isSpectator() || this.witch.distanceToSqr(player) > 1296.0D) {
                tag.remove("HexTargetPlayer");
                return false;
            }
            this.targetPlayer = player;
            this.returnVillagePos = VillagerLifeHandler.getVillageCenter(level, this.witch.blockPosition());
            return true;
        }
        @Override
        public void start() {
            this.throwStage = 0;
            this.stageDelayTicks = 0;
            this.witch.setTarget(null);
            this.witch.getNavigation().moveTo(this.targetPlayer, 1.05D);
        }
        @Override
        public boolean canContinueToUse() {
            if (isWitchInRaid(this.witch)) {
                return false;
            }
            if (this.throwStage < 2) {
                return this.targetPlayer != null && this.targetPlayer.isAlive() && !this.targetPlayer.isCreative();
            }
            return this.returnVillagePos != null
                    && this.witch.blockPosition().distSqr(this.returnVillagePos) > 64.0D
                    && !this.witch.getNavigation().isDone();
        }
        @Override
        public void tick() {
            if (!(this.witch.level() instanceof ServerLevel level)) return;
            if (this.witch.getTarget() != null) {
                this.witch.setTarget(null);
            }
            if (this.throwStage == 0 && this.targetPlayer != null) {
                this.witch.getLookControl().setLookAt(this.targetPlayer, 30.0F, 30.0F);
                double distSq = this.witch.distanceToSqr(this.targetPlayer);
                if (distSq > 64.0D) {
                    if (this.witch.tickCount % 10 == 0) {
                        this.witch.getNavigation().moveTo(this.targetPlayer, 1.05D);
                    }
                    return;
                }
                this.witch.getNavigation().stop();
                this.throwSplashPotionAt(level, this.targetPlayer, Potions.WEAKNESS);
                this.throwStage = 1;
                this.stageDelayTicks = 25;
            } else if (this.throwStage == 1 && this.targetPlayer != null) {
                this.witch.getLookControl().setLookAt(this.targetPlayer, 30.0F, 30.0F);
                if (--this.stageDelayTicks <= 0) {
                    this.throwSplashPotionAt(level, this.targetPlayer, Potions.SLOWNESS);
                    this.throwStage = 2;
                    if (this.returnVillagePos != null) {
                        this.witch.getNavigation().moveTo(
                                this.returnVillagePos.getX() + 0.5D,
                                this.returnVillagePos.getY(),
                                this.returnVillagePos.getZ() + 0.5D,
                                0.85D
                        );
                    }
                }
            } else if (this.throwStage == 2 && this.returnVillagePos != null) {
                if (this.witch.tickCount % 20 == 0) {
                    this.witch.getNavigation().moveTo(
                            this.returnVillagePos.getX() + 0.5D,
                            this.returnVillagePos.getY(),
                            this.returnVillagePos.getZ() + 0.5D,
                            0.85D
                    );
                }
            }
        }
        private void throwSplashPotionAt(ServerLevel level, Player target, Potion potionType) {
            Vec3 targetVel = target.getDeltaMovement();
            double dx = target.getX() + targetVel.x - this.witch.getX();
            double dy = target.getEyeY() - 1.1D - this.witch.getY();
            double dz = target.getZ() + targetVel.z - this.witch.getZ();
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            ThrownPotion thrownPotion = new ThrownPotion(level, this.witch);
            thrownPotion.setItem(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), potionType));
            thrownPotion.shoot(dx, dy + horizDist * 0.2D, dz, 0.75F, 4.0F);
            this.witch.playSound(SoundEvents.WITCH_THROW, 1.0F, 0.9F + level.random.nextFloat() * 0.2F);
            level.addFreshEntity(thrownPotion);
        }
        @Override
        public void stop() {
            this.witch.getPersistentData().remove("HexTargetPlayer");
            this.targetPlayer = null;
            this.returnVillagePos = null;
            this.throwStage = 0;
        }
    }
    // =========================================================================
// GOAL C: GENERAL VILLAGERS BARTERING WITH PEACEFUL WITCHES
// =========================================================================
    private static class VillagerTradeWithWitchGoal extends Goal {
        private final Villager villager;
        private Witch targetWitch;
        private int cooldown = 300;
        public VillagerTradeWithWitchGoal(Villager villager) {
            this.villager = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override
        public boolean canUse() {
            if (!(this.villager.level() instanceof ServerLevel level) || this.villager.isBaby()) {
                return false;
            }
            if (level.getRaidAt(this.villager.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;
            List peacefulWitches = level.getEntitiesOfClass(
                    Witch.class,
                    this.villager.getBoundingBox().inflate(16.0D),
                    w -> w.isAlive() && !isWitchInRaid(w) && w.getTarget() == null && !w.getPersistentData().hasUUID("HexTargetPlayer")
            );
            if (peacefulWitches.isEmpty()) return false;
            this.targetWitch = (Witch) peacefulWitches.get(this.villager.getRandom().nextInt(peacefulWitches.size()));
            return true;
        }
        @Override
        public void start() {
            this.cooldown = 1000;
            this.villager.getNavigation().moveTo(this.targetWitch, 0.55D);
        }
        @Override
        public boolean canContinueToUse() {
            return this.targetWitch != null
                    && this.targetWitch.isAlive()
                    && !isWitchInRaid(this.targetWitch)
                    && this.targetWitch.getTarget() == null
                    && this.villager.distanceToSqr(this.targetWitch) < 324.0D;
        }
        @Override
        public void tick() {
            if (this.targetWitch == null) return;
            this.villager.getLookControl().setLookAt(this.targetWitch);
            this.targetWitch.getLookControl().setLookAt(this.villager);
            if (this.villager.distanceToSqr(this.targetWitch) < 5.0D && this.villager.level() instanceof ServerLevel level) {
                this.villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
                this.targetWitch.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POTION));
                this.villager.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
                this.villager.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400, 0));
                this.villager.getInventory().addItem(new ItemStack(
                        level.random.nextBoolean() ? Items.GLOWSTONE_DUST : Items.REDSTONE, 1));
                this.villager.playSound(SoundEvents.VILLAGER_TRADE, 1.0F, 1.0F);
                this.targetWitch.playSound(SoundEvents.WITCH_CELEBRATE, 0.9F, 1.05F);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        this.villager.getX(), this.villager.getY() + 2.0D, this.villager.getZ(),
                        5, 0.25D, 0.2D, 0.25D, 0.0D);
                level.sendParticles(ParticleTypes.WITCH,
                        this.targetWitch.getX(), this.targetWitch.getY() + 2.0D, this.targetWitch.getZ(),
                        5, 0.25D, 0.2D, 0.25D, 0.0D);
                this.targetWitch = null;
            }
        }
        @Override
        public void stop() {
            this.villager.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (this.targetWitch != null) {
                this.targetWitch.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            this.targetWitch = null;
        }
    }
    // =========================================================================
// GOAL D: VILLAGE DEFENDER WITCH (Throws Offensive Potions at Raiders + Heals Allies)
// =========================================================================
    private static class VillageDefenderWitchGoal extends Goal {
        private final Witch witch;
        private LivingEntity actionTarget;
        private boolean isHealingAlly;
        private int throwCooldown = 0;
        public VillageDefenderWitchGoal(Witch witch) {
            this.witch = witch;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override
        public boolean canUse() {
            if (!isVillageDefenderWitch(this.witch) || !(this.witch.level() instanceof ServerLevel level)) {
                return false;
            }
// 1. Check for injured village defenders/villagers/golems within 12 blocks first
            List injuredAllies = level.getEntitiesOfClass(
                    LivingEntity.class,
                    this.witch.getBoundingBox().inflate(12.0D),
                    e -> e.isAlive()
                            && e.getHealth() < e.getMaxHealth() * 0.70F
                            && (e instanceof AbstractVillagerExtra
                            || e instanceof IronGolem
                            || e instanceof Villager
                            || (e instanceof Player p && !p.isCreative() && !p.isSpectator()))
            );
            if (!injuredAllies.isEmpty()) {
                this.actionTarget = (LivingEntity) injuredAllies.get(0);
                this.isHealingAlly = true;
                return true;
            }
// 2. Otherwise target the closest invading Raider (excluding other Village Defender Witches)
            List raiders = level.getEntitiesOfClass(
                    Raider.class,
                    this.witch.getBoundingBox().inflate(24.0D),
                    r -> r.isAlive() && !(r instanceof Witch w && isVillageDefenderWitch(w))
            );
            if (!raiders.isEmpty()) {
                raiders.sort(Comparator.comparingDouble(e -> this.witch.distanceToSqr((Entity) e)));
                this.actionTarget = (LivingEntity) raiders.get(0);
                this.isHealingAlly = false;
                return true;
            }
            return false;
        }
        @Override
        public void tick() {
            if (this.actionTarget == null || !this.actionTarget.isAlive() || !(this.witch.level() instanceof ServerLevel level)) {
                return;
            }
            if (this.witch.getTarget() != null) {
                this.witch.setTarget(null);
            }
            this.witch.getLookControl().setLookAt(this.actionTarget, 30.0F, 30.0F);
            double distSq = this.witch.distanceToSqr(this.actionTarget);
            if (distSq > 100.0D) {
                if (this.witch.tickCount % 10 == 0) {
                    this.witch.getNavigation().moveTo(this.actionTarget, 1.05D);
                }
                return;
            }
            this.witch.getNavigation().stop();
            if (--this.throwCooldown <= 0) {
                this.throwCooldown = 45;
                Potion potionToThrow;
                if (this.isHealingAlly) {
                    potionToThrow = this.actionTarget.hasEffect(MobEffects.REGENERATION) ? Potions.HEALING : Potions.REGENERATION;
                } else if (distSq > 64.0D && !this.actionTarget.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
                    potionToThrow = Potions.SLOWNESS;
                } else if (!this.actionTarget.hasEffect(MobEffects.WEAKNESS)) {
                    potionToThrow = Potions.WEAKNESS;
                } else if (!this.actionTarget.hasEffect(MobEffects.POISON) && !(this.actionTarget instanceof AbstractIllager && this.actionTarget.getHealth() < 8.0F)) {
                    potionToThrow = Potions.POISON;
                } else {
                    potionToThrow = Potions.HARMING;
                }
                this.throwPotionAt(level, this.actionTarget, potionToThrow);
            }
        }
        private void throwPotionAt(ServerLevel level, LivingEntity target, Potion potionType) {
            Vec3 vel = target.getDeltaMovement();
            double dx = target.getX() + vel.x - this.witch.getX();
            double dy = target.getEyeY() - 1.1D - this.witch.getY();
            double dz = target.getZ() + vel.z - this.witch.getZ();
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            ThrownPotion thrown = new ThrownPotion(level, this.witch);
            thrown.setItem(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), potionType));
            thrown.shoot(dx, dy + horizDist * 0.2D, dz, 0.85F, 3.5F);
            this.witch.playSound(SoundEvents.WITCH_THROW, 1.0F, 0.95F);
            level.addFreshEntity(thrown);
        }
    }
}