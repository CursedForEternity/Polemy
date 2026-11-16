package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillageGuardSpawns {

    // Tracks the last processed day number per dimension (handles sleeping & /time set)
    private static final Map<ServerLevel, Long> LAST_PROCESSED_DAY = new WeakHashMap<>();

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        // Check every 100 ticks (5 seconds) if a new in-game day has started
        if (level.getGameTime() % 100L != 0L) {
            return;
        }

        long currentDay = level.getDayTime() / 24000L;
        Long lastDay = LAST_PROCESSED_DAY.get(level);

        if (lastDay == null) {
            LAST_PROCESSED_DAY.put(level, currentDay);
            return;
        }

        if (currentDay > lastDay) {
            LAST_PROCESSED_DAY.put(level, currentDay);
            checkLoadedVillagesForDailySpawns(level);
        }
    }

    private static void checkLoadedVillagesForDailySpawns(ServerLevel level) {
        List<BlockPos> processedCenters = new ArrayList<>();

        for (Villager villager : level.getEntities(EntityType.VILLAGER, v -> v.isAlive() && !v.isBaby())) {
            BlockPos pos = villager.blockPosition();
            if (!level.isLoaded(pos) || !level.isVillage(pos) || level.getRaidAt(pos) != null) {
                continue;
            }

            BlockPos villageCenter = level.getPoiManager()
                    .findClosest(poi -> poi.is(PoiTypes.MEETING), pos, 48, PoiManager.Occupancy.ANY)
                    .orElse(pos);

            boolean alreadyChecked = processedCenters.stream()
                    .anyMatch(center -> center.distSqr(villageCenter) < 6400.0D);
            if (alreadyChecked) {
                continue;
            }
            processedCenters.add(villageCenter);

            evaluateDailyVillageSpawn(level, villageCenter);
        }
    }

    private static void evaluateDailyVillageSpawn(ServerLevel level, BlockPos center) {
        AABB villageBox = new AABB(center).inflate(48.0D);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, villageBox, v -> !v.isBaby());
        if (villagers.isEmpty()) {
            return;
        }

        List<EntityType<? extends AbstractVillagerExtra>> eligibleTypes = getEligibleUnitTypes(level, villagers, villageBox);
        if (eligibleTypes.isEmpty()) {
            return;
        }

        // 45% chance each new day to spawn 1 to 3 units of an eligible specialist type
        if (level.random.nextFloat() < 0.45F) {
            EntityType<? extends AbstractVillagerExtra> chosenType =
                    eligibleTypes.get(level.random.nextInt(eligibleTypes.size()));
            int count = 1 + level.random.nextInt(3); // 1, 2, or 3 units

            for (int i = 0; i < count; i++) {
                trySpawnUnitNear(level, chosenType, center);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        // Only trigger when a Villager or one of your custom Guards dies
        if (!(event.getEntity() instanceof Villager) && !(event.getEntity() instanceof AbstractVillagerExtra)) {
            return;
        }

        BlockPos deathPos = event.getEntity().blockPosition();
        Raid raid = level.getRaidAt(deathPos);
        if (raid == null || !raid.isActive()) {
            return;
        }

        BlockPos center = raid.getCenter();
        AABB raidBox = new AABB(center).inflate(64.0D);
        List<Villager> survivingVillagers = level.getEntitiesOfClass(
                Villager.class,
                raidBox,
                v -> v.isAlive() && !v.isBaby() && v != event.getEntity()
        );

        List<EntityType<? extends AbstractVillagerExtra>> eligibleTypes =
                getEligibleUnitTypes(level, survivingVillagers, raidBox);
        if (eligibleTypes.isEmpty()) {
            return;
        }

        // Pick an eligible guard type based on surviving specialists (Weaponsmith/Armorer, Fletcher, Cleric)
        EntityType<? extends AbstractVillagerExtra> reinforcementType =
                eligibleTypes.get(level.random.nextInt(eligibleTypes.size()));

        // Try to convert a surviving unemployed/nitwit villager into the defender first
        List<Villager> recruits = new ArrayList<>(survivingVillagers.stream()
                .filter(v -> v.getVillagerData().getProfession() == VillagerProfession.NONE ||
                        v.getVillagerData().getProfession() == VillagerProfession.NITWIT)
                .toList());

        if (!recruits.isEmpty()) {
            Villager recruit = recruits.get(level.random.nextInt(recruits.size()));
            convertVillagerToGuard(level, recruit, reinforcementType);
        } else {
            // Fallback: spawn a reinforcement near the raid center if no unemployed villagers remain
            trySpawnUnitNear(level, reinforcementType, center);
        }
    }

    private static List<EntityType<? extends AbstractVillagerExtra>> getEligibleUnitTypes(
            ServerLevel level, List<Villager> villagers, AABB areaBox) {

        boolean hasSmith = villagers.stream().anyMatch(v ->
                v.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH ||
                        v.getVillagerData().getProfession() == VillagerProfession.ARMORER);
        boolean hasFletcher = villagers.stream().anyMatch(v ->
                v.getVillagerData().getProfession() == VillagerProfession.FLETCHER);
        boolean hasCleric = villagers.stream().anyMatch(v ->
                v.getVillagerData().getProfession() == VillagerProfession.CLERIC);

        List<AbstractVillagerExtra> existingGuards = level.getEntitiesOfClass(
                AbstractVillagerExtra.class, areaBox, AbstractVillagerExtra::isAlive);

        List<EntityType<? extends AbstractVillagerExtra>> eligible = new ArrayList<>();
        if (hasSmith) {
            eligible.add(MobsPolemy.BAILIFF.get());
        }
        if (hasFletcher) {
            eligible.add(MobsPolemy.SENTRY.get());
        }
        if (hasCleric) {
            eligible.add(MobsPolemy.INVOKER.get());
        }
        return eligible;
    }

    private static void convertVillagerToGuard(ServerLevel level, Villager villager,
                                               EntityType<? extends AbstractVillagerExtra> guardType) {
        BlockPos pos = villager.blockPosition();
        AbstractVillagerExtra guard = guardType.create(level);
        if (guard != null) {
            guard.moveTo(villager.getX(), villager.getY(), villager.getZ(), villager.getYRot(), villager.getXRot());
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.CONVERSION, null, null);
            level.addFreshEntity(guard);
            level.playSound(null, pos, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
            villager.discard();
        }
    }

    private static void trySpawnUnitNear(ServerLevel level, EntityType<? extends AbstractVillagerExtra> type, BlockPos origin) {
        for (int attempt = 0; attempt < 15; attempt++) {
            int x = origin.getX() + level.random.nextInt(24) - 12;
            int z = origin.getZ() + level.random.nextInt(24) - 12;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos spawnPos = new BlockPos(x, y, z);

            if (level.isLoaded(spawnPos)
                    && level.isVillage(spawnPos)
                    && level.getBlockState(spawnPos).isAir()
                    && level.getBlockState(spawnPos.below()).isSolid()) {
                type.spawn(level, spawnPos, MobSpawnType.NATURAL);
                break;
            }
        }
    }
}