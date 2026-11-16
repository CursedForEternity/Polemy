package cursed.polemy.core.events;

import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

@Mod.EventBusSubscriber(modid = "polemy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillageNetworkHandler {

    private static final double MAX_NEIGHBOR_DIST = 256.0D;
    private static final double MAX_NEIGHBOR_DIST_SQ = MAX_NEIGHBOR_DIST * MAX_NEIGHBOR_DIST;

    // Tracks cooldowns so a sending village only dispatches reinforcements once per raid/siege
    private static final Map<BlockPos, Long> REINFORCEMENT_COOLDOWNS = new HashMap<>();

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        // Every 600 ticks (30 seconds): process inter-village trade & check for raids/monster sieges
        if (level.getGameTime() % 600L == 0L) {
            List<BlockPos> activeCenters = getLoadedVillageCenters(level);
            processInterVillageTrade(level, activeCenters);
            processAlliedReinforcements(level, activeCenters);
        }
    }

    // =========================================================================
    // 1. DISCOVER ALL LOADED VILLAGES IN DIMENSION
    // =========================================================================
    public static List<BlockPos> getLoadedVillageCenters(ServerLevel level) {
        List<BlockPos> centers = new ArrayList<>();
        for (Villager villager : level.getEntities(EntityType.VILLAGER, v -> v.isAlive() && !v.isBaby())) {
            BlockPos pos = villager.blockPosition();
            if (!level.isLoaded(pos) || !level.isVillage(pos)) continue;

            BlockPos center = level.getPoiManager()
                    .findClosest(poi -> poi.is(PoiTypes.MEETING), pos, 48, PoiManager.Occupancy.ANY)
                    .orElse(pos);

            boolean duplicate = centers.stream().anyMatch(c -> c.distSqr(center) < 6400.0D);
            if (!duplicate) {
                centers.add(center);
            }
        }
        return centers;
    }

    @Nullable
    public static BlockPos findHealthyNeighbor(ServerLevel level, BlockPos origin) {
        List<BlockPos> centers = getLoadedVillageCenters(level);
        BlockPos bestNeighbor = null;
        double closestDistSq = MAX_NEIGHBOR_DIST_SQ;

        for (BlockPos candidate : centers) {
            if (candidate.distSqr(origin) < 6400.0D) continue; // Same village
            double distSq = candidate.distSqr(origin);
            if (distSq <= closestDistSq && isVillageSustainable(level, candidate)) {
                closestDistSq = distSq;
                bestNeighbor = candidate;
            }
        }
        return bestNeighbor;
    }

    public static boolean isVillageSustainable(ServerLevel level, BlockPos center) {
        if (level.getRaidAt(center) != null) return false;
        int deaths = VillagerLifeHandler.getVillageDeaths(level, center);
        if (deaths >= 4) return false;

        int beds = (int) level.getPoiManager().getCountInRange(poi -> poi.is(PoiTypes.HOME), center, 48, PoiManager.Occupancy.ANY);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(48.0D), v -> v.isAlive() && !v.isBaby());
        return beds > villagers.size() && villagers.size() >= 2;
    }

    // =========================================================================
    // 2. INTER-VILLAGE TRADE & SURVIVAL BOOST (Max 256 Blocks)
    // =========================================================================
    private static void processInterVillageTrade(ServerLevel level, List<BlockPos> centers) {
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                BlockPos villageA = centers.get(i);
                BlockPos villageB = centers.get(j);

                if (villageA.distSqr(villageB) > MAX_NEIGHBOR_DIST_SQ) continue;
                if (level.getRaidAt(villageA) != null || level.getRaidAt(villageB) != null) continue;

                List<Villager> popA = level.getEntitiesOfClass(Villager.class, new AABB(villageA).inflate(48.0D), v -> v.isAlive() && !v.isBaby());
                List<Villager> popB = level.getEntitiesOfClass(Villager.class, new AABB(villageB).inflate(48.0D), v -> v.isAlive() && !v.isBaby());

                long jobsA = popA.stream().map(v -> v.getVillagerData().getProfession())
                        .filter(p -> p != VillagerProfession.NONE && p != VillagerProfession.NITWIT).distinct().count();
                long jobsB = popB.stream().map(v -> v.getVillagerData().getProfession())
                        .filter(p -> p != VillagerProfession.NONE && p != VillagerProfession.NITWIT).distinct().count();

                // Both villages must have active specialist jobs to sustain trade
                if (jobsA >= 2 && jobsB >= 2) {
                    applyTradeProsperity(level, villageA, popA);
                    applyTradeProsperity(level, villageB, popB);
                }
            }
        }
    }

    private static void applyTradeProsperity(ServerLevel level, BlockPos center, List<Villager> villagers) {
        // Trade boosts morale & supplies: reduces death count penalty so villages survive longer
        VillagerLifeHandler.reduceVillageDeaths(level, center, 1);

        for (Villager v : villagers) {
            if (v.getHealth() < v.getMaxHealth()) {
                v.heal(4.0F);
            }
            if (level.random.nextFloat() < 0.35F) {
                v.getInventory().addItem(new ItemStack(Items.BREAD, 2));
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 2.0D, v.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.0D);
            }
        }
    }

    // =========================================================================
    // 3. ALLIED REINFORCEMENTS DURING RAID OR MONSTER SIEGE (Up to 16 Guards)
    // =========================================================================
    private static void processAlliedReinforcements(ServerLevel level, List<BlockPos> centers) {
        for (BlockPos endangered : centers) {
            boolean underRaid = level.getRaidAt(endangered) != null && level.getRaidAt(endangered).isActive();
            int hostileCount = level.getEntitiesOfClass(Monster.class, new AABB(endangered).inflate(48.0D), Monster::isAlive).size();
            boolean underMonsterSiege = hostileCount >= 4;

            if (!underRaid && !underMonsterSiege) continue;

            // Enforce a 5-minute (6000 tick) cooldown per endangered village
            long lastHelp = REINFORCEMENT_COOLDOWNS.getOrDefault(endangered, -10000L);
            if (level.getGameTime() - lastHelp < 6000L) continue;

            // Find a peaceful neighboring village within 256 blocks that has specialist conditions
            for (BlockPos allyVillage : centers) {
                if (allyVillage.equals(endangered) || allyVillage.distSqr(endangered) > MAX_NEIGHBOR_DIST_SQ) continue;
                if (level.getRaidAt(allyVillage) != null) continue;

                List<Villager> allySpecialists = level.getEntitiesOfClass(Villager.class, new AABB(allyVillage).inflate(48.0D), v -> v.isAlive() && !v.isBaby());

                int smithCount = (int) allySpecialists.stream().filter(v ->
                        v.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH ||
                        v.getVillagerData().getProfession() == VillagerProfession.ARMORER).count();
                int fletcherCount = (int) allySpecialists.stream().filter(v ->
                        v.getVillagerData().getProfession() == VillagerProfession.FLETCHER).count();
                int clericCount = (int) allySpecialists.stream().filter(v ->
                        v.getVillagerData().getProfession() == VillagerProfession.CLERIC).count();

                // 4 guards per 1 specialist profession, capped at 16 guards total per village
                int bailiffsToSend = smithCount * 4;
                int sentriesToSend = fletcherCount * 4;
                int invokersToSend = clericCount * 4;

                int totalCalculated = bailiffsToSend + sentriesToSend + invokersToSend;
                if (totalCalculated <= 0) continue;

                REINFORCEMENT_COOLDOWNS.put(endangered, level.getGameTime());
                dispatchReinforcementWave(level, allyVillage, endangered, bailiffsToSend, sentriesToSend, invokersToSend);
                break; // One neighbor dispatches per wave
            }
        }
    }

    private static void dispatchReinforcementWave(ServerLevel level, BlockPos fromVillage, BlockPos targetVillage,
                                                  int bailiffs, int sentries, int invokers) {
        // Spawn on the edge of the endangered village facing the ally village so they march in
        double dx = fromVillage.getX() - targetVillage.getX();
        double dz = fromVillage.getZ() - targetVillage.getZ();
        double dist = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        BlockPos entryEdge = targetVillage.offset((int) ((dx / dist) * 32.0D), 0, (int) ((dz / dist) * 32.0D));
        int spawnedTotal = 0;
        spawnedTotal += spawnSquad(level, MobsPolemy.BAILIFF.get(), entryEdge, targetVillage, Math.min(bailiffs, 16 - spawnedTotal));
        spawnedTotal += spawnSquad(level, MobsPolemy.SENTRY.get(), entryEdge, targetVillage, Math.min(sentries, 16 - spawnedTotal));
        spawnedTotal += spawnSquad(level, MobsPolemy.INVOKER.get(), entryEdge, targetVillage, Math.min(invokers, 16 - spawnedTotal));
        if (spawnedTotal > 0) {
            level.playSound(null, targetVillage, SoundEvents.RAID_HORN.get(), SoundSource.NEUTRAL, 1.5F, 1.2F);
        }
    }
    private static int spawnSquad(ServerLevel level, EntityType<? extends AbstractVillagerExtra> type,
                                  BlockPos entryEdge, BlockPos defendCenter, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 12; attempt++) {
                int x = entryEdge.getX() + level.random.nextInt(12) - 6;
                int z = entryEdge.getZ() + level.random.nextInt(12) - 6;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (level.isLoaded(pos) && level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid()) {
                    AbstractVillagerExtra guard = type.create(level);
                    if (guard != null) {
                        guard.moveTo(x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
                        guard.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.REINFORCEMENT, null, null);
// Assign the endangered village as their home so they defend it and stay inside its bounds
                        guard.setHomeVillagePos(defendCenter);
                        guard.getNavigation().moveTo(defendCenter.getX(), defendCenter.getY(), defendCenter.getZ(), 1.15D);
                        level.addFreshEntity(guard);
                        spawned++;
                    }
                    break;
                }
            }
        }
        return spawned;
    }
}