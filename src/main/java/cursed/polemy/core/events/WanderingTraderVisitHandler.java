package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.mobs.Rogue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

import static cursed.polemy.registry.entities.MobsPolemy.ROGUE;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WanderingTraderVisitHandler {

    // Inject village-trading AI & emergency Rogue morph whenever a Wandering Trader spawns
    @SubscribeEvent
    public static void onTraderJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        // Priority 0 overrides vanilla PanicGoal (priority 1) during raids or monster proximity
        trader.goalSelector.addGoal(0, new TraderEmergencyRogueGoal(trader));
        trader.goalSelector.addGoal(3, new TraderMingleWithVillagersGoal(trader));
    }

    // Instantly morphs the Wandering Trader into a Rogue the exact tick it is hit
    @SubscribeEvent
    public static void onTraderHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof WanderingTrader trader)
                || !(trader.level() instanceof ServerLevel level)) {
            return;
        }

        // Ignore environmental damage (fall, suffocation); morph on any entity/projectile attack
        if (event.getSource().getEntity() != null) {
            morphTraderIntoRogue(level, trader, event.getSource().getEntity());
        }
    }

    public static void morphTraderIntoRogue(ServerLevel level, WanderingTrader trader, @Nullable Entity attacker) {
        if (!trader.isAlive()) return;

        CompoundTag savedTraderNbt = new CompoundTag();
        trader.saveWithoutId(savedTraderNbt);

        Rogue rogue = ROGUE.get().create(level);
        if (rogue != null) {
            rogue.moveTo(trader.getX(), trader.getY(), trader.getZ(), trader.getYRot(), trader.getXRot());
            rogue.finalizeSpawn(level, level.getCurrentDifficultyAt(trader.blockPosition()),
                    MobSpawnType.CONVERSION, null, null);
            rogue.setMorphedTraderData(savedTraderNbt);

            // Set attacker as target (AbstractVillagerExtra#canAttack automatically ignores Creative players)
            if (attacker instanceof LivingEntity livingAttacker) {
                rogue.setTarget(livingAttacker);
            }

            level.addFreshEntity(rogue);
            level.sendParticles(ParticleTypes.SMOKE,
                    trader.getX(), trader.getY() + 1.0D, trader.getZ(),
                    14, 0.3D, 0.4D, 0.3D, 0.03D);

            trader.discard();
        }
    }

    // Periodically spawn a Wandering Trader near a peaceful loaded village
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        // Every 20 ticks (1 second): immediately morph any Wandering Trader caught in a Raid
        if (level.getGameTime() % 20L == 0L) {
            for (WanderingTrader trader : level.getEntities(EntityType.WANDERING_TRADER, LivingEntity::isAlive)) {
                var raid = level.getRaidAt(trader.blockPosition());
                boolean raidNearby = (raid != null && raid.isActive())
                        || !level.getEntitiesOfClass(net.minecraft.world.entity.raid.Raider.class,
                        trader.getBoundingBox().inflate(48.0D),
                        r -> r.isAlive() && !(r instanceof Witch)).isEmpty();

                if (raidNearby) {
                    morphTraderIntoRogue(level, trader, null);
                }
            }
        }

        // Check once every 10 minutes (12,000 ticks) during daytime for peaceful village visits
        if (level.getGameTime() % 12000L != 0L || !level.isDay()) {
            return;
        }

        List<BlockPos> villages = VillageNetworkHandler.getLoadedVillageCenters(level);
        for (BlockPos center : villages) {
            if (level.getRaidAt(center) != null) continue;

            boolean traderPresent = !level.getEntitiesOfClass(WanderingTrader.class, new AABB(center).inflate(64.0D)).isEmpty();
            if (traderPresent) continue;

            if (level.random.nextFloat() < 0.35F) {
                spawnTraderAtVillage(level, center);
                break;
            }
        }
    }

    private static void spawnTraderAtVillage(ServerLevel level, BlockPos center) {
        for (int attempt = 0; attempt < 15; attempt++) {
            int x = center.getX() + level.random.nextInt(32) - 16;
            int z = center.getZ() + level.random.nextInt(32) - 16;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);

            if (level.isLoaded(pos) && level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid()) {
                WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(level, pos, MobSpawnType.EVENT);
                if (trader != null) {
                    trader.setDespawnDelay(24000);
                    trader.getNavigation().moveTo(center.getX(), center.getY(), center.getZ(), 0.65D);
                }
                break;
            }
        }
    }

    // =========================================================================
    // GOAL 1: Morphs Wandering Trader into a Rogue during Raids or Monster Proximity
    // =========================================================================
    private static class TraderEmergencyRogueGoal extends Goal {
        private final WanderingTrader trader;

        public TraderEmergencyRogueGoal(WanderingTrader trader) {
            this.trader = trader;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!(this.trader.level() instanceof ServerLevel level)) {
                return false;
            }

            boolean activeRaid = level.getRaidAt(this.trader.blockPosition()) != null
                    && level.getRaidAt(this.trader.blockPosition()).isActive();
            boolean directlyHurt = this.trader.getLastHurtByMob() != null;
            boolean monsterClose = !level.getEntitiesOfClass(
                    Monster.class,
                    this.trader.getBoundingBox().inflate(12.0D),
                    m -> m.isAlive() && !(m instanceof Creeper)
            ).isEmpty();

            return activeRaid || directlyHurt || monsterClose;
        }

        @Override
        public void start() {
            if (this.trader.level() instanceof ServerLevel level) {
                morphTraderIntoRogue(level, this.trader, this.trader.getLastHurtByMob());
            }
        }
    }

    // =========================================================================
    // GOAL 2: Wandering Trader Mingles & Trades with Village Residents
    // =========================================================================
    private static class TraderMingleWithVillagersGoal extends Goal {
        private final WanderingTrader trader;
        private Villager tradePartner;
        private int cooldown = 200;

        public TraderMingleWithVillagersGoal(WanderingTrader trader) {
            this.trader = trader;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!(this.trader.level() instanceof ServerLevel level) || level.getRaidAt(this.trader.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            List<Villager> villagers = level.getEntitiesOfClass(Villager.class, this.trader.getBoundingBox().inflate(24.0D),
                    v -> v.isAlive() && !v.isBaby());
            if (villagers.isEmpty()) return false;

            this.tradePartner = villagers.get(this.trader.getRandom().nextInt(villagers.size()));
            return true;
        }

        @Override
        public void start() {
            this.cooldown = 800;
            this.trader.getNavigation().moveTo(this.tradePartner, 0.6D);
        }

        @Override
        public void tick() {
            if (this.tradePartner == null || !this.tradePartner.isAlive()) return;
            this.trader.getLookControl().setLookAt(this.tradePartner);

            if (this.trader.distanceToSqr(this.tradePartner) < 5.0D && this.trader.level() instanceof ServerLevel level) {
                this.trader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
                this.tradePartner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));

                this.tradePartner.getInventory().addItem(new ItemStack(Items.EMERALD, 1));
                this.tradePartner.heal(4.0F);
                this.trader.playSound(SoundEvents.WANDERING_TRADER_TRADE, 1.0F, 1.0F);

                level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        this.tradePartner.getX(), this.tradePartner.getY() + 2.0D, this.tradePartner.getZ(),
                        5, 0.25D, 0.2D, 0.25D, 0.0D);
                this.tradePartner = null;
            }
        }

        @Override
        public void stop() {
            this.trader.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (this.tradePartner != null) {
                this.tradePartner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
    }
}