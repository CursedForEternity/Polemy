package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumSet;
import java.util.List;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WanderingTraderVisitHandler {

    // Inject village-trading AI whenever a Wandering Trader spawns
    @SubscribeEvent
    public static void onTraderJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        trader.goalSelector.addGoal(3, new TraderMingleWithVillagersGoal(trader));
    }

    // Periodically spawn a Wandering Trader near a peaceful loaded village
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        // Check once every 10 minutes (12,000 ticks) during daytime
        if (level.getGameTime() % 12000L != 0L || !level.isDay()) {
            return;
        }

        List<BlockPos> villages = VillageNetworkHandler.getLoadedVillageCenters(level);
        for (BlockPos center : villages) {
            if (level.getRaidAt(center) != null) continue;

            // Skip if a Wandering Trader is already visiting this village
            boolean traderPresent = !level.getEntitiesOfClass(WanderingTrader.class, new AABB(center).inflate(64.0D)).isEmpty();
            if (traderPresent) continue;

            // 35% chance per check to visit a village
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
                    trader.setDespawnDelay(24000); // Stays in the village for 1 full day (20 mins)
                    trader.getNavigation().moveTo(center.getX(), center.getY(), center.getZ(), 0.65D);
                }
                break;
            }
        }
    }

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
            this.cooldown = 800; // Trades with a villager every 40 seconds
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
