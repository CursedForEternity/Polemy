package cursed.polemy.core.events;

import cursed.polemy.Polemy;
import cursed.polemy.core.entity.AbstractVillagerExtra;
import cursed.polemy.registry.entities.MobsPolemy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;

@Mod.EventBusSubscriber(modid = Polemy.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillagerLifeHandler {

    // Tracks recent villager deaths per village center for Nitwit recruitment & Village Abandonment
    private static final Map<BlockPos, Integer> VILLAGE_DEATH_COUNTS = new HashMap<>();

    @SubscribeEvent
    public static void onVillagerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager)) {
            return;
        }

        // Inject custom behavior goals cleanly without breaking vanilla Brain AI
        villager.goalSelector.addGoal(1, new PersonalHomeAndHomelessGoal(villager));
        villager.goalSelector.addGoal(2, new StayNearVillageAnchorGoal(villager));
        villager.goalSelector.addGoal(3, new ChurchRaidShelterGoal(villager));
        villager.goalSelector.addGoal(3, new MasonConstructionGoal(villager));
        villager.goalSelector.addGoal(3, new LibrarianEducateNitwitGoal(villager));
        villager.goalSelector.addGoal(3, new BuildIronGolemGoal(villager));
        villager.goalSelector.addGoal(3, new FarmerAnimalBreedAndTradeGoal(villager));
        villager.goalSelector.addGoal(3, new ButcherCullAnimalsGoal(villager));
        villager.goalSelector.addGoal(4, new RepairIronGolemGoal(villager));
        villager.goalSelector.addGoal(4, new SpecialistEmergencyMorphGoal(villager));
        villager.goalSelector.addGoal(5, new ClericSermonGoal(villager));
        villager.goalSelector.addGoal(6, new ButcherFoodExchangeGoal(villager));
        villager.goalSelector.addGoal(6, new AbandonVillageGoal(villager));
        villager.goalSelector.addGoal(7, new NitwitMilitiaRecruitmentGoal(villager));

    }

    @SubscribeEvent
    public static void onVillagerDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof Villager villager) {
            BlockPos center = getVillageCenter(level, villager.blockPosition());
            VILLAGE_DEATH_COUNTS.merge(center, 1, Integer::sum);
        }
    }

    // Apply trade discount when a player interacts with a Cleric after attending their sermon
    @SubscribeEvent
    public static void onPlayerTradeWithCleric(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof Villager villager)) {
            return;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.CLERIC) {
            return;
        }

        Player player = event.getEntity();
        CompoundTag tag = villager.getPersistentData();
        String key = "SermonAttendee_" + player.getUUID();

        if (tag.getBoolean(key)) {
            tag.remove(key); // Consume the one-time sermon blessing
            for (MerchantOffer offer : villager.getOffers()) {
                offer.addToSpecialPriceDiff(-4); // 4-emerald discount on next trade session
            }
            villager.level().playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.1F);
        }
    }

    public static BlockPos getVillageCenter(ServerLevel level, BlockPos pos) {
        return level.getPoiManager()
                .findClosest(poi -> poi.is(PoiTypes.MEETING), pos, 64, PoiManager.Occupancy.ANY)
                .orElse(pos);
    }

    public static int getVillageDeaths(ServerLevel level, BlockPos pos) {
        return VILLAGE_DEATH_COUNTS.getOrDefault(getVillageCenter(level, pos), 0);
    }

    public static void reduceVillageDeaths(ServerLevel level, BlockPos pos, int amount) {
        BlockPos center = getVillageCenter(level, pos);
        int current = VILLAGE_DEATH_COUNTS.getOrDefault(center, 0);
        if (current > 0) {
            VILLAGE_DEATH_COUNTS.put(center, Math.max(0, current - amount));
        }
    }

    private static class ChurchRaidShelterGoal extends Goal {
        private final Villager villager;
        private BlockPos churchPos;

        public ChurchRaidShelterGoal(Villager villager) {
            this.villager = villager;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!(this.villager.level() instanceof ServerLevel level) || this.villager.isBaby()) {
                return false;
            }
            Raid raid = level.getRaidAt(this.villager.blockPosition());
            if (raid == null || !raid.isActive()) {
                return false;
            }

            if (this.villager.getVillagerData().getProfession() == VillagerProfession.CLERIC) {
                this.churchPos = level.getPoiManager()
                        .findClosest(poi -> poi.is(PoiTypes.CLERIC), this.villager.blockPosition(), 48, PoiManager.Occupancy.ANY)
                        .orElse(null);
                return this.churchPos != null && this.villager.blockPosition().distSqr(this.churchPos) > 9.0D;
            }

            // Non-clerics: follow a nearby Cleric if fewer than 4 villagers are already sheltering with them
            List<Villager> clerics = level.getEntitiesOfClass(Villager.class, this.villager.getBoundingBox().inflate(32.0D),
                    v -> v.isAlive() && v.getVillagerData().getProfession() == VillagerProfession.CLERIC);
            if (!clerics.isEmpty()) {
                Villager cleric = clerics.get(0);
                long followers = level.getEntitiesOfClass(Villager.class, cleric.getBoundingBox().inflate(5.0D),
                        v -> v != cleric && !v.isBaby()).size();
                if (followers < 4 && this.villager.distanceToSqr(cleric) > 12.0D) {
                    this.churchPos = cleric.blockPosition();
                    return true;
                }
            }
            return false;
        }

        @Override
        public void start() {
            if (this.churchPos != null) {
                this.villager.getNavigation().moveTo(this.churchPos.getX(), this.churchPos.getY(), this.churchPos.getZ(), 0.85D);
            }
        }
    }

    private static class ButcherFoodExchangeGoal extends Goal {
        private final Villager butcher;
        private Villager partner;
        private int cooldown = 400;

        public ButcherFoodExchangeGoal(Villager villager) {
            this.butcher = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (this.butcher.getVillagerData().getProfession() != VillagerProfession.BUTCHER || this.butcher.isBaby()) {
                return false;
            }
            if (!(this.butcher.level() instanceof ServerLevel level) || level.getRaidAt(this.butcher.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            List<Villager> neighbors = level.getEntitiesOfClass(Villager.class, this.butcher.getBoundingBox().inflate(12.0D),
                    v -> v != this.butcher && v.isAlive() && !v.isBaby());
            if (neighbors.isEmpty()) return false;

            this.partner = neighbors.get(this.butcher.getRandom().nextInt(neighbors.size()));
            return true;
        }

        @Override
        public void start() {
            this.cooldown = 1200; // Once per minute
            this.butcher.getNavigation().moveTo(this.partner, 0.55D);
        }

        @Override
        public void tick() {
            if (this.partner == null || !this.partner.isAlive()) return;
            this.butcher.getLookControl().setLookAt(this.partner);

            if (this.butcher.distanceToSqr(this.partner) < 4.5D && this.butcher.level() instanceof ServerLevel level) {
                // Visual food & emerald swap
                this.butcher.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COOKED_BEEF));
                this.partner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));

                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.partner.getX(), this.partner.getY() + 2.0D, this.partner.getZ(), 4, 0.2D, 0.2D, 0.2D, 0.0D);
                this.butcher.playSound(SoundEvents.VILLAGER_TRADE, 1.0F, 1.0F);
                this.partner = null;
            }
        }

        @Override
        public void stop() {
            this.butcher.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (this.partner != null) {
                this.partner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
    }

    private static class NitwitMilitiaRecruitmentGoal extends Goal {
        private final Villager nitwit;
        private int checkTimer = 300;

        public NitwitMilitiaRecruitmentGoal(Villager villager) {
            this.nitwit = villager;
        }

        @Override
        public boolean canUse() {
            if (this.nitwit.getVillagerData().getProfession() != VillagerProfession.NITWIT || this.nitwit.isBaby()) {
                return false;
            }
            if (!(this.nitwit.level() instanceof ServerLevel level) || level.getRaidAt(this.nitwit.blockPosition()) != null) {
                return false;
            }
            if (--this.checkTimer > 0) return false;
            this.checkTimer = 600; // Check every 30s

            int deaths = VillagerLifeHandler.getVillageDeaths(level, this.nitwit.blockPosition());
            return deaths > 3 && this.nitwit.getRandom().nextFloat() < 0.35F;
        }

        @Override
        public void start() {
            if (!(this.nitwit.level() instanceof ServerLevel level)) return;

            AABB box = this.nitwit.getBoundingBox().inflate(48.0D);
            List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby());

            List<EntityType<? extends AbstractVillagerExtra>> options = new ArrayList<>();
            if (villagers.stream().anyMatch(v -> v.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH || v.getVillagerData().getProfession() == VillagerProfession.ARMORER)) {
                options.add(MobsPolemy.BAILIFF.get());
            }
            if (villagers.stream().anyMatch(v -> v.getVillagerData().getProfession() == VillagerProfession.FLETCHER)) {
                options.add(MobsPolemy.SENTRY.get());
            }
            if (villagers.stream().anyMatch(v -> v.getVillagerData().getProfession() == VillagerProfession.CLERIC)) {
                options.add(MobsPolemy.INVOKER.get());
            }

            if (!options.isEmpty()) {
                EntityType<? extends AbstractVillagerExtra> chosen = options.get(this.nitwit.getRandom().nextInt(options.size()));
                AbstractVillagerExtra guard = chosen.create(level);
                if (guard != null) {
                    guard.moveTo(this.nitwit.getX(), this.nitwit.getY(), this.nitwit.getZ(), this.nitwit.getYRot(), this.nitwit.getXRot());
                    guard.finalizeSpawn(level, level.getCurrentDifficultyAt(this.nitwit.blockPosition()), MobSpawnType.CONVERSION, null, null);
                    level.addFreshEntity(guard);
                    level.playSound(null, this.nitwit.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
                    this.nitwit.discard();
                }
            }
        }
    }

    private static class AbandonVillageGoal extends Goal {
        private final Villager villager;
        private BlockPos fleeOrigin;
        @Nullable
        private BlockPos refugeeDestination;

        public AbandonVillageGoal(Villager villager) {
            this.villager = villager;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!(this.villager.level() instanceof ServerLevel level) || this.villager.isBaby()) {
                return false;
            }
            if (level.getRaidAt(this.villager.blockPosition()) != null) {
                return false;
            }

            BlockPos center = VillagerLifeHandler.getVillageCenter(level, this.villager.blockPosition());
            int deaths = VillagerLifeHandler.getVillageDeaths(level, center);
            boolean hasGuards = !level.getEntitiesOfClass(AbstractVillagerExtra.class, new AABB(center).inflate(48.0D)).isEmpty();
            boolean hasBeds = level.getPoiManager().getCountInRange(poi -> poi.is(PoiTypes.HOME), center, 48, PoiManager.Occupancy.ANY) > 0;

            if (deaths >= 5 && !hasGuards && !hasBeds) {
                this.fleeOrigin = center;
                // Look for a sustainable neighboring village within 256 blocks to migrate to
                this.refugeeDestination = VillageNetworkHandler.findHealthyNeighbor(level, center);
                return true;
            }
            return false;
        }

        @Override
        public void tick() {
            if (this.fleeOrigin == null || !(this.villager.level() instanceof ServerLevel level)) return;

            // Case 1: Sustainable neighbor exists -> migrate in 16-block pathfinding steps toward that village
            if (this.refugeeDestination != null) {
                double distSq = this.villager.blockPosition().distSqr(this.refugeeDestination);
                if (distSq <= 400.0D) { // Arrived within 20 blocks of the new village!
                    this.villager.getPersistentData().putInt("HomelessCycles", 0);
                    this.fleeOrigin = null;
                    this.refugeeDestination = null;
                    return;
                }

                double dx = this.refugeeDestination.getX() - this.villager.getX();
                double dz = this.refugeeDestination.getZ() - this.villager.getZ();
                double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
                this.villager.getNavigation().moveTo(
                        this.villager.getX() + (dx / len) * 16.0D,
                        this.villager.getY(),
                        this.villager.getZ() + (dz / len) * 16.0D,
                        0.8D
                );
                return;
            }

            // Case 2: No sustainable neighbor -> flee away from ruined village and despawn
            double dx = this.villager.getX() - this.fleeOrigin.getX();
            double dz = this.villager.getZ() - this.fleeOrigin.getZ();
            double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            this.villager.getNavigation().moveTo(
                    this.villager.getX() + (dx / len) * 16.0D,
                    this.villager.getY(),
                    this.villager.getZ() + (dz / len) * 16.0D,
                    0.75D
            );

            boolean farFromPoi = level.getPoiManager().findClosest(poi -> true, this.villager.blockPosition(), 64, PoiManager.Occupancy.ANY).isEmpty();
            if (farFromPoi && level.getNearestPlayer(this.villager, 32.0D) == null) {
                this.villager.discard();
            }
        }
    }


    private static class RepairIronGolemGoal extends Goal {
        private final Villager smith;
        private IronGolem targetGolem;
        private int cooldown = 200;

        public RepairIronGolemGoal(Villager villager) {
            this.smith = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            VillagerProfession prof = this.smith.getVillagerData().getProfession();
            if (prof != VillagerProfession.ARMORER && prof != VillagerProfession.WEAPONSMITH && prof != VillagerProfession.TOOLSMITH) {
                return false;
            }
            if (!(this.smith.level() instanceof ServerLevel level) || level.getRaidAt(this.smith.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            List<IronGolem> damagedGolems = level.getEntitiesOfClass(IronGolem.class, this.smith.getBoundingBox().inflate(20.0D),
                    g -> g.isAlive() && g.getHealth() < g.getMaxHealth());
            if (damagedGolems.isEmpty()) return false;

            this.targetGolem = damagedGolems.get(0);
            return true;
        }

        @Override
        public void start() {
            this.cooldown = 300;
            this.smith.getNavigation().moveTo(this.targetGolem, 0.65D);
        }

        @Override
        public void tick() {
            if (this.targetGolem == null || !this.targetGolem.isAlive() || this.targetGolem.getHealth() >= this.targetGolem.getMaxHealth()) {
                return;
            }
            this.smith.getLookControl().setLookAt(this.targetGolem);

            if (this.smith.distanceToSqr(this.targetGolem) < 6.0D) {
                this.smith.swing(InteractionHand.MAIN_HAND);
                this.targetGolem.heal(15.0F);
                this.targetGolem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.0F, 1.0F);
                if (this.smith.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.targetGolem.getX(), this.targetGolem.getY() + 1.5D, this.targetGolem.getZ(), 5, 0.4D, 0.4D, 0.4D, 0.0D);
                }
                this.targetGolem = null;
            }
        }
    }

    private static class ClericSermonGoal extends Goal {
        private final Villager cleric;
        private BlockPos pulpitPos;
        private int sermonTicks;
        private long nextSermonDay = 2L;

        public ClericSermonGoal(Villager villager) {
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

            long currentDay = level.getDayTime() / 24000L;
            if (currentDay < this.nextSermonDay) return false;

            this.pulpitPos = level.getPoiManager()
                    .findClosest(poi -> poi.is(PoiTypes.CLERIC) || poi.is(PoiTypes.MEETING), this.cleric.blockPosition(), 32, PoiManager.Occupancy.ANY)
                    .orElse(null);
            return this.pulpitPos != null;
        }

        @Override
        public void start() {
            this.sermonTicks = 300; // 15-second sermon
            this.nextSermonDay = (this.cleric.level().getDayTime() / 24000L) + 2L; // Every 2 days
            this.cleric.getNavigation().moveTo(this.pulpitPos.getX(), this.pulpitPos.getY(), this.pulpitPos.getZ(), 0.6D);
        }

        @Override
        public boolean canContinueToUse() {
            return this.sermonTicks > 0;
        }

        @Override
        public void tick() {
            this.sermonTicks--;
            if (!(this.cleric.level() instanceof ServerLevel level)) return;

            // Call nearby villagers to gather around the Cleric
            if (this.sermonTicks % 40 == 0) {
                this.cleric.playSound(SoundEvents.VILLAGER_AMBIENT, 1.0F, 0.95F);
                level.sendParticles(ParticleTypes.ENCHANT, this.cleric.getX(), this.cleric.getY() + 2.1D, this.cleric.getZ(), 6, 0.4D, 0.2D, 0.4D, 0.02D);

                for (Villager listener : level.getEntitiesOfClass(Villager.class, this.cleric.getBoundingBox().inflate(16.0D), v -> v != this.cleric && v.isAlive())) {
                    if (listener.distanceToSqr(this.cleric) > 16.0D) {
                        listener.getNavigation().moveTo(this.cleric, 0.55D);
                    } else {
                        listener.getLookControl().setLookAt(this.cleric);
                    }
                }
            }

            // Grant trade discount flag to any player attending within 8 blocks
            for (Player player : level.getEntitiesOfClass(Player.class, this.cleric.getBoundingBox().inflate(8.0D))) {
                String key = "SermonAttendee_" + player.getUUID();
                if (!this.cleric.getPersistentData().getBoolean(key)) {
                    this.cleric.getPersistentData().putBoolean(key, true);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 2.0D, player.getZ(), 5, 0.3D, 0.2D, 0.3D, 0.0D);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerSleepInBed(PlayerSleepInBedEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level && event.getPos() != null) {
            BlockPos bedPos = event.getPos();
            BlockState state = level.getBlockState(bedPos);
            if (state.getBlock() instanceof BedBlock) {
                if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
                    bedPos = bedPos.relative(state.getValue(BedBlock.FACING));
                }
                VillageBedSavedData.get(level).claimBedForPlayer(event.getEntity().getUUID(), bedPos);
            }
        }
    }

    @SubscribeEvent
    public static void onBedBroken(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockState state = event.getState();
        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }

        BlockPos headPos = event.getPos();
        // Normalize to the HEAD block so it matches the stored POI coordinate
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            headPos = headPos.relative(state.getValue(BedBlock.FACING));
        }

        UUID formerOwnerId = VillageBedSavedData.get(level).releaseBed(headPos);
        if (formerOwnerId != null && level.getEntity(formerOwnerId) instanceof Villager formerOwner) {
            formerOwner.getBrain().eraseMemory(MemoryModuleType.HOME);
            formerOwner.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
            level.sendParticles(
                    ParticleTypes.ANGRY_VILLAGER,
                    formerOwner.getX(),
                    formerOwner.getY() + 2.0D,
                    formerOwner.getZ(),
                    4, 0.25D, 0.2D, 0.25D, 0.0D
            );
        }
    }


    @SubscribeEvent
    public static void onVillagerInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof Villager villager)) {
            return;
        }
        // Prevent trading while Librarian is actively educating a Nitwit
        if (villager.getPersistentData().getBoolean("IsEducatingNitwit")) {
            villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
            event.setCanceled(true);
            return;
        }
    }

    private static class PersonalHomeAndHomelessGoal extends Goal {
        private final Villager villager;
        private long lastCheckedDay = -1L;

        public PersonalHomeAndHomelessGoal(Villager villager) {
            this.villager = villager;
        }

        @Override
        public boolean canUse() {
            return this.villager.level() instanceof ServerLevel && !this.villager.isBaby();
        }

        @Override
        public void tick() {
            if (!(this.villager.level() instanceof ServerLevel level)) return;
            if (this.villager.tickCount % 40 != 0) return; // Check every 2 seconds

            VillageBedSavedData bedData = VillageBedSavedData.get(level);
            BlockPos ownedBed = bedData.getOwnedBed(this.villager.getUUID());

            // Verify existing bed still exists and wasn't claimed by a player
            if (ownedBed != null) {
                boolean validBedBlock = level.getBlockState(ownedBed).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
                if (!validBedBlock || !bedData.isBedAvailable(level, ownedBed, this.villager.getUUID())) {
                    ownedBed = null;
                    this.villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
                }
            }

            // Try to claim an unoccupied bed within 48 blocks
            if (ownedBed == null) {
                Optional<BlockPos> freeBed = level.getPoiManager().findAll(
                        poi -> poi.is(PoiTypes.HOME),
                        pos -> bedData.isBedAvailable(level, pos, this.villager.getUUID()),
                        this.villager.blockPosition(),
                        48,
                        PoiManager.Occupancy.ANY
                ).findFirst();

                if (freeBed.isPresent()) {
                    ownedBed = freeBed.get();
                    bedData.claimBedForVillager(ownedBed, this.villager.getUUID());
                    this.villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,
                            net.minecraft.core.GlobalPos.of(level.dimension(), ownedBed));
                    this.villager.getPersistentData().putInt("HomelessCycles", 0);
                }
            }

            // Track daily cycles without a bed (5 cycles = becomes a homeless Nitwit)
            long currentDay = level.getDayTime() / 24000L;
            if (this.lastCheckedDay == -1L) {
                this.lastCheckedDay = currentDay;
            } else if (currentDay > this.lastCheckedDay) {
                this.lastCheckedDay = currentDay;
                if (ownedBed == null) {
                    int cycles = this.villager.getPersistentData().getInt("HomelessCycles") + 1;
                    this.villager.getPersistentData().putInt("HomelessCycles", cycles);

                    if (cycles >= 5 && this.villager.getVillagerData().getProfession() != VillagerProfession.NITWIT) {
                        this.villager.setVillagerData(this.villager.getVillagerData().setProfession(VillagerProfession.NITWIT).setLevel(1));
                        this.villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
                        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, this.villager.getX(), this.villager.getY() + 2.0D, this.villager.getZ(), 5, 0.3D, 0.2D, 0.3D, 0.0D);
                    }
                }
            }
        }
    }

    private static class LibrarianEducateNitwitGoal extends Goal {
        private final Villager librarian;
        private Villager studentNitwit;
        private BlockPos targetPoiPos;
        private VillagerProfession targetProfession;
        private int lessonTicks;
        private int cooldown = 200;

        public LibrarianEducateNitwitGoal(Villager villager) {
            this.librarian = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (this.librarian.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN || this.librarian.isBaby()) {
                return false;
            }
            if (!(this.librarian.level() instanceof ServerLevel level) || level.getRaidAt(this.librarian.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) {
                return false;
            }

            List<Villager> nitwits = level.getEntitiesOfClass(
                    Villager.class,
                    this.librarian.getBoundingBox().inflate(12.0D),
                    v -> v.isAlive() && !v.isBaby() && v.getVillagerData().getProfession() == VillagerProfession.NITWIT
            );
            if (nitwits.isEmpty()) {
                return false;
            }

            Optional<BlockPos> freeJobSite = level.getPoiManager().findClosest(
                    holder -> this.getProfessionForPoi(holder) != null,
                    this.librarian.blockPosition(),
                    32,
                    PoiManager.Occupancy.HAS_SPACE
            );

            if (freeJobSite.isPresent()) {
                this.targetPoiPos = freeJobSite.get();
                this.targetProfession = level.getPoiManager()
                        .getType(this.targetPoiPos)
                        .map(this::getProfessionForPoi)
                        .orElse(null);

                if (this.targetProfession != null) {
                    this.studentNitwit = nitwits.get(0);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void start() {
            this.lessonTicks = 200;
            this.cooldown = 1200;
            this.librarian.getPersistentData().putBoolean("IsEducatingNitwit", true);
            this.librarian.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOOK));
            this.librarian.getNavigation().moveTo(this.targetPoiPos.getX(), this.targetPoiPos.getY(), this.targetPoiPos.getZ(), 0.6D);
        }

        @Override
        public boolean canContinueToUse() {
            return this.studentNitwit != null
                    && this.studentNitwit.isAlive()
                    && this.studentNitwit.getVillagerData().getProfession() == VillagerProfession.NITWIT
                    && this.lessonTicks > 0;
        }

        @Override
        public void tick() {
            if (!(this.librarian.level() instanceof ServerLevel level)) {
                return;
            }

            if (this.studentNitwit.distanceToSqr(this.targetPoiPos.getX(), this.targetPoiPos.getY(), this.targetPoiPos.getZ()) > 9.0D) {
                this.studentNitwit.getNavigation().moveTo(this.targetPoiPos.getX(), this.targetPoiPos.getY(), this.targetPoiPos.getZ(), 0.6D);
                return;
            }

            this.lessonTicks--;
            this.librarian.getLookControl().setLookAt(this.studentNitwit);
            this.studentNitwit.getLookControl().setLookAt(this.librarian);

            if (this.lessonTicks % 20 == 0) {
                level.sendParticles(
                        ParticleTypes.ENCHANT,
                        this.studentNitwit.getX(),
                        this.studentNitwit.getY() + 2.0D,
                        this.studentNitwit.getZ(),
                        6, 0.3D, 0.2D, 0.3D, 0.02D
                );
                this.librarian.playSound(SoundEvents.VILLAGER_WORK_LIBRARIAN, 0.8F, 1.0F);
            }

            if (this.lessonTicks <= 0) {
                this.studentNitwit.setVillagerData(this.studentNitwit.getVillagerData().setProfession(this.targetProfession).setLevel(1));
                this.studentNitwit.getBrain().setMemory(
                        MemoryModuleType.JOB_SITE,
                        GlobalPos.of(level.dimension(), this.targetPoiPos)
                );
                this.studentNitwit.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, 1.0F);
                level.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        this.studentNitwit.getX(),
                        this.studentNitwit.getY() + 2.0D,
                        this.studentNitwit.getZ(),
                        8, 0.3D, 0.3D, 0.3D, 0.0D
                );
            }
        }

        @Override
        public void stop() {
            this.librarian.getPersistentData().remove("IsEducatingNitwit");
            this.librarian.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.studentNitwit = null;
        }

        @Nullable
        private VillagerProfession getProfessionForPoi(Holder<PoiType> holder) {
            for (VillagerProfession prof : ForgeRegistries.VILLAGER_PROFESSIONS.getValues()) {
                if (prof != VillagerProfession.NONE && prof != VillagerProfession.NITWIT && prof.heldJobSite().test(holder)) {
                    return prof;
                }
            }
            return null;
        }
    }


    private static class ButcherCullAnimalsGoal extends Goal {
        private final Villager butcher;
        private Animal cullTarget;
        private int cooldown = 400;

        public ButcherCullAnimalsGoal(Villager villager) {
            this.butcher = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (this.butcher.getVillagerData().getProfession() != VillagerProfession.BUTCHER || this.butcher.isBaby()) {
                return false;
            }
            if (!(this.butcher.level() instanceof ServerLevel level) || level.getRaidAt(this.butcher.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            List<Animal> adults = level.getEntitiesOfClass(
                    Animal.class,
                    this.butcher.getBoundingBox().inflate(24.0D),
                    a -> a.isAlive() && !a.isBaby() && !a.isInLove()
            );

            for (Animal candidate : adults) {
                long sameSpecies = adults.stream().filter(a -> a.getClass() == candidate.getClass()).count();
                if (sameSpecies > 4) {
                    this.cullTarget = candidate;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void start() {
            this.cooldown = 900;
            this.butcher.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            this.butcher.getNavigation().moveTo(this.cullTarget, 0.65D);
        }

        @Override
        public void tick() {
            if (this.cullTarget == null || !this.cullTarget.isAlive()) return;
            this.butcher.getLookControl().setLookAt(this.cullTarget);

            if (this.butcher.distanceToSqr(this.cullTarget) < 5.0D && this.butcher.level() instanceof ServerLevel level) {
                this.butcher.swing(InteractionHand.MAIN_HAND);
                this.cullTarget.hurt(level.damageSources().mobAttack(this.butcher), 25.0F);
                this.butcher.playSound(SoundEvents.VILLAGER_WORK_BUTCHER, 1.0F, 1.0F);
                this.cullTarget = null;
            }
        }

        @Override
        public void stop() {
            this.butcher.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.cullTarget = null;
        }
    }


    private static class FarmerAnimalBreedAndTradeGoal extends Goal {
        private final Villager farmer;
        private Animal mateA;
        private Animal mateB;
        private int cooldown = 300;

        public FarmerAnimalBreedAndTradeGoal(Villager villager) {
            this.farmer = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (this.farmer.getVillagerData().getProfession() != VillagerProfession.FARMER || this.farmer.isBaby()) {
                return false;
            }
            if (!(this.farmer.level() instanceof ServerLevel level) || level.getRaidAt(this.farmer.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            List<Animal> animals = level.getEntitiesOfClass(
                    Animal.class,
                    this.farmer.getBoundingBox().inflate(20.0D),
                    a -> a.isAlive() && !a.isBaby() && a.getAge() == 0 && !a.isInLove()
            );

            for (Animal first : animals) {
                long speciesCount = level.getEntitiesOfClass(
                        Animal.class,
                        this.farmer.getBoundingBox().inflate(24.0D),
                        a -> a.isAlive() && !a.isBaby() && a.getClass() == first.getClass()
                ).size();

                if (speciesCount > 4) continue;

                for (Animal second : animals) {
                    if (first != second && first.getClass() == second.getClass()) {
                        this.mateA = first;
                        this.mateB = second;
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void start() {
            this.cooldown = 800;
            this.farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
            this.farmer.getNavigation().moveTo(this.mateA, 0.6D);
        }

        @Override
        public void tick() {
            if (this.mateA == null || this.mateB == null || !this.mateA.isAlive() || !this.mateB.isAlive()) return;
            this.farmer.getLookControl().setLookAt(this.mateA);

            if (this.farmer.distanceToSqr(this.mateA) < 6.0D) {
                this.mateA.setInLove(null);
                this.mateB.setInLove(null);
                this.farmer.swing(InteractionHand.MAIN_HAND);
                this.farmer.playSound(SoundEvents.VILLAGER_WORK_FARMER, 1.0F, 1.0F);

                if (this.farmer.level() instanceof ServerLevel level) {
                    List<Villager> neighbors = level.getEntitiesOfClass(
                            Villager.class,
                            this.farmer.getBoundingBox().inflate(10.0D),
                            v -> v != this.farmer && v.isAlive()
                    );
                    if (!neighbors.isEmpty() && !this.farmer.getOffers().isEmpty()) {
                        ItemStack tradeFood = this.farmer.getOffers().get(0).getResult().copy();
                        if (tradeFood.isEdible()) {
                            neighbors.get(0).getInventory().addItem(tradeFood);
                            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, neighbors.get(0).getX(), neighbors.get(0).getY() + 2.0D, neighbors.get(0).getZ(), 3, 0.2D, 0.2D, 0.2D, 0.0D);
                        }
                    }
                }
                this.mateA = null;
                this.mateB = null;
            }
        }

        @Override
        public void stop() {
            this.farmer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.mateA = null;
            this.mateB = null;
        }
    }

    private static class SpecialistEmergencyMorphGoal extends Goal {
        private final Villager villager;

        public SpecialistEmergencyMorphGoal(Villager villager) {
            this.villager = villager;
        }

        @Override
        public boolean canUse() {
            if (!(this.villager.level() instanceof ServerLevel level) || this.villager.isBaby()) {
                return false;
            }
            if (this.getGuardTypeForProfession(this.villager.getVillagerData().getProfession()) == null) {
                return false;
            }

            // Immediate danger check: hurt recently, active raid nearby, or hostile monster within 16 blocks
            boolean recentlyHurt = this.villager.getLastHurtByMob() != null;
            boolean activeRaid = level.getRaidAt(this.villager.blockPosition()) != null
                    && level.getRaidAt(this.villager.blockPosition()).isActive();
            boolean monsterClose = !level.getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Monster.class,
                    this.villager.getBoundingBox().inflate(16.0D),
                    m -> m.isAlive() && !(m instanceof net.minecraft.world.entity.monster.Creeper)
            ).isEmpty();

            return recentlyHurt || activeRaid || monsterClose;
        }

        @Override
        public void start() {
            if (!(this.villager.level() instanceof ServerLevel level)) return;

            EntityType<? extends AbstractVillagerExtra> guardType =
                    this.getGuardTypeForProfession(this.villager.getVillagerData().getProfession());
            if (guardType == null) return;

            // Save the villager's complete NBT so trades, XP, and level restore after 2 minutes of safety
            CompoundTag savedVillagerNbt = new CompoundTag();
            this.villager.saveWithoutId(savedVillagerNbt);

            AbstractVillagerExtra defender = guardType.create(level);
            if (defender != null) {
                defender.moveTo(this.villager.getX(), this.villager.getY(), this.villager.getZ(),
                        this.villager.getYRot(), this.villager.getXRot());
                defender.finalizeSpawn(level, level.getCurrentDifficultyAt(this.villager.blockPosition()),
                        MobSpawnType.CONVERSION, null, null);
                defender.setMorphedVillagerData(savedVillagerNbt);

                level.addFreshEntity(defender);
                level.playSound(null, this.villager.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON,
                        SoundSource.NEUTRAL, 1.2F, 1.0F);
                level.sendParticles(ParticleTypes.CLOUD,
                        this.villager.getX(), this.villager.getY() + 1.0D, this.villager.getZ(),
                        10, 0.3D, 0.5D, 0.3D, 0.05D);

                this.villager.discard();
            }
        }

        @Nullable
        private EntityType<? extends AbstractVillagerExtra> getGuardTypeForProfession(VillagerProfession prof) {
            if (prof == VillagerProfession.FLETCHER) {
                return MobsPolemy.SENTRY.get();
            }
            if (prof == VillagerProfession.ARMORER
                    || prof == VillagerProfession.WEAPONSMITH
                    || prof == VillagerProfession.TOOLSMITH) {
                return MobsPolemy.BAILIFF.get();
            }
            if (prof == VillagerProfession.CLERIC) {
                return MobsPolemy.INVOKER.get();
            }
            return null;
        }
    }

    private static class BuildIronGolemGoal extends Goal {
        private final Villager smith;
        private BlockPos buildSite;
        private int buildTicks;
        private int cooldown = 200;

        public BuildIronGolemGoal(Villager villager) {
            this.smith = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            VillagerProfession prof = this.smith.getVillagerData().getProfession();
            if (prof != VillagerProfession.ARMORER
                    && prof != VillagerProfession.WEAPONSMITH
                    && prof != VillagerProfession.TOOLSMITH) {
                return false;
            }
            if (!(this.smith.level() instanceof ServerLevel level) || this.smith.isBaby()) {
                return false;
            }
            if (--this.cooldown > 0) {
                return false;
            }

            BlockPos center = VillagerLifeHandler.getVillageCenter(level, this.smith.blockPosition());
            AABB villageBox = new AABB(center).inflate(48.0D);

            List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, villageBox, IronGolem::isAlive);

            // Prevent over-spawning if 2+ golems are already present
            if (golems.size() >= 2) {
                return false;
            }

            // Trigger if no golems exist OR all existing golems are below 25% max health
            boolean needsNewGolem = golems.isEmpty()
                    || golems.stream().allMatch(g -> g.getHealth() < g.getMaxHealth() * 0.25F);

            if (!needsNewGolem) {
                return false;
            }

            this.buildSite = center;
            return true;
        }

        @Override
        public void start() {
            this.buildTicks = 100; // 5-second forging/assembly animation
            this.cooldown = 1200;  // 1-minute cooldown per smith
            this.smith.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_BLOCK));
            this.smith.getNavigation().moveTo(this.buildSite.getX(), this.buildSite.getY(), this.buildSite.getZ(), 0.7D);
        }

        @Override
        public boolean canContinueToUse() {
            return this.buildTicks > 0 && this.smith.isAlive();
        }

        @Override
        public void tick() {
            if (!(this.smith.level() instanceof ServerLevel level) || this.buildSite == null) {
                return;
            }

            // Walk toward the village forge/bell center first
            if (this.smith.blockPosition().distSqr(this.buildSite) > 16.0D) {
                this.smith.getNavigation().moveTo(this.buildSite.getX(), this.buildSite.getY(), this.buildSite.getZ(), 0.7D);
                return;
            }

            // Arrived at build site: hammer and forge the Iron Golem
            this.smith.getNavigation().stop();
            this.smith.getLookControl().setLookAt(this.buildSite.getX() + 0.5D, this.buildSite.getY() + 1.0D, this.buildSite.getZ() + 0.5D);
            this.buildTicks--;

            if (this.buildTicks % 20 == 0) {
                this.smith.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, this.smith.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.8F, 1.1F);
                level.sendParticles(
                        ParticleTypes.LAVA,
                        this.smith.getX(),
                        this.smith.getY() + 1.2D,
                        this.smith.getZ(),
                        4, 0.3D, 0.3D, 0.3D, 0.0D
                );
            }

            if (this.buildTicks <= 0) {
                this.spawnBuiltGolem(level);
            }
        }

        private void spawnBuiltGolem(ServerLevel level) {
            for (int attempt = 0; attempt < 15; attempt++) {
                int x = this.smith.getBlockX() + level.random.nextInt(8) - 4;
                int z = this.smith.getBlockZ() + level.random.nextInt(8) - 4;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos spawnPos = new BlockPos(x, y, z);

                if (level.getBlockState(spawnPos).isAir()
                        && level.getBlockState(spawnPos.above()).isAir()
                        && level.getBlockState(spawnPos.below()).isSolid()) {

                    IronGolem golem = EntityType.IRON_GOLEM.create(level);
                    if (golem != null) {
                        golem.moveTo(x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
                        golem.setPlayerCreated(false); // Marks it as a village-aligned defender
                        level.addFreshEntity(golem);

                        level.playSound(null, spawnPos, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.2F, 0.9F);
                        level.sendParticles(
                                ParticleTypes.HAPPY_VILLAGER,
                                x + 0.5D, y + 1.5D, z + 0.5D,
                                10, 0.5D, 0.5D, 0.5D, 0.0D
                        );
                    }
                    break;
                }
            }
        }

        @Override
        public void stop() {
            this.smith.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.buildTicks = 0;
        }
    }

    private static class MasonConstructionGoal extends Goal {
        private final Villager mason;
        private BlockPos buildOrigin;
        private ResourceLocation chosenTemplateId;
        private Rotation chosenRotation;
        private int remainingBuildTicks;
        private int cooldown = 60; // Quick 3-second initial check so newly spawned Masons start promptly

        public MasonConstructionGoal(Villager villager) {
            this.mason = villager;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private Rotation pickRotationFacingNearestRoad(ServerLevel level, StructureTemplate template, BlockPos origin) {
            BlockPos nearestRoad = this.findNearestRoadBlock(level, origin, 20);
            if (nearestRoad == null) {
                return Rotation.getRandom(level.random);
            }

            // Determine the compass direction from the plot center toward the nearest road
            net.minecraft.core.Vec3i baseSize = template.getSize();
            double dx = nearestRoad.getX() - (origin.getX() + baseSize.getX() / 2.0D);
            double dz = nearestRoad.getZ() - (origin.getZ() + baseSize.getZ() / 2.0D);
            net.minecraft.core.Direction desiredFront = net.minecraft.core.Direction.getNearest(dx, 0.0D, dz);

            // Detect the template's default front direction from its DoorBlock (defaults to SOUTH if none)
            net.minecraft.core.Direction templateFront = this.detectTemplateDoorFacing(level, template);

            // Pick the Rotation that turns templateFront into desiredFront
            for (Rotation rot : Rotation.values()) {
                if (rot.rotate(templateFront) == desiredFront) {
                    return rot;
                }
            }
            return Rotation.getRandom(level.random);
        }

        private net.minecraft.core.Direction detectTemplateDoorFacing(ServerLevel level, StructureTemplate template) {
            for (BlockState state : this.getTemplateBlockStates(level, template)) {
                if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                        && state.hasProperty(net.minecraft.world.level.block.DoorBlock.FACING)) {
                    return state.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
                }
            }
            return net.minecraft.core.Direction.SOUTH;
        }

        @Nullable
        private BlockPos findNearestRoadBlock(ServerLevel level, BlockPos origin, int radius) {
            BlockPos nearest = null;
            double bestDistSq = radius * radius;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int wx = origin.getX() + dx;
                    int wz = origin.getZ() + dz;
                    int wy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                    BlockPos check = new BlockPos(wx, wy, wz);
                    BlockState state = level.getBlockState(check);

                    if (state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                            || state.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)
                            || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)) {
                        double distSq = check.distSqr(origin);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            nearest = check;
                        }
                    }
                }
            }
            return nearest;
        }


        @Override
        public boolean canUse() {
            if (this.mason.getVillagerData().getProfession() != VillagerProfession.MASON
                    || this.mason.getVillagerData().getLevel() < 2
                    || this.mason.isBaby()) {
                return false;
            }
            if (!(this.mason.level() instanceof ServerLevel level) || level.getRaidAt(this.mason.blockPosition()) != null) {
                return false;
            }
            if (--this.cooldown > 0) return false;

            BlockPos center = VillagerLifeHandler.getVillageCenter(level, this.mason.blockPosition());
            AABB villageBox = new AABB(center).inflate(48.0D);

            long bedCount = level.getPoiManager().getCountInRange(poi -> poi.is(PoiTypes.HOME), center, 48, PoiManager.Occupancy.ANY);
            long adultCount = level.getEntitiesOfClass(Villager.class, villageBox, v -> v.isAlive() && !v.isBaby()).size();

            List<ResourceLocation> packTemplates = this.collectTemplatesFromVillagePack(level, center);
            if (packTemplates.isEmpty()) {
                this.cooldown = 400;
                return false;
            }

            if (bedCount < adultCount && bedCount < 16) {
                this.chosenTemplateId = this.pickHouseTemplate(level, packTemplates);
            } else {
                this.chosenTemplateId = this.pickMissingPoiTemplate(level, center, packTemplates);
                if (this.chosenTemplateId == null && bedCount < (adultCount + 3) && bedCount < 16) {
                    this.chosenTemplateId = this.pickHouseTemplate(level, packTemplates);
                }
            }

            if (this.chosenTemplateId == null) {
                this.cooldown = 400;
                return false;
            }

            Optional<StructureTemplate> templateOpt = level.getStructureManager().get(this.chosenTemplateId);
            if (templateOpt.isEmpty()) {
                this.cooldown = 200;
                return false;
            }

            StructureTemplate template = templateOpt.get();

            // 1. Find a valid flat plot using the base structure dimensions
            this.buildOrigin = this.findValidPlot(level, center, template.getSize());
            if (this.buildOrigin == null) {
                this.cooldown = 200;
                return false;
            }

            // 2. Rotate the building so its front door faces the nearest village road
            this.chosenRotation = this.pickRotationFacingNearestRoad(level, template, this.buildOrigin);

            // 3. Re-validate if 90/270 degree rotation swapped the X and Z footprint dimensions
            net.minecraft.core.Vec3i rotatedSize = template.getSize(this.chosenRotation);
            if (rotatedSize.getX() != template.getSize().getX() || rotatedSize.getZ() != template.getSize().getZ()) {
                BlockPos recheckedOrigin = this.findValidPlot(level, center, rotatedSize);
                if (recheckedOrigin == null) {
                    this.cooldown = 200;
                    return false;
                }
                this.buildOrigin = recheckedOrigin;
                this.chosenRotation = this.pickRotationFacingNearestRoad(level, template, this.buildOrigin);
            }

            long solidBlockCount = 0;
            for (BlockState state : this.getTemplateBlockStates(level, template)) {
                if (!state.isAir() && !state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) {
                    solidBlockCount++;
                }
            }
            this.remainingBuildTicks = (int) Math.max(100, solidBlockCount * 2);
            return true;
        }

        @Override
        public void start() {
            this.cooldown = 12000; // 10-minute cooldown after completing/starting a build
            this.mason.getPersistentData().putBoolean("IsConstructing", true);
            this.mason.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_BRICKS));
            this.mason.getNavigation().moveTo(this.buildOrigin.getX() - 1.5D, this.buildOrigin.getY(), this.buildOrigin.getZ() - 1.5D, 0.7D);
        }

        @Override
        public boolean canContinueToUse() {
            if (!(this.mason.level() instanceof ServerLevel level)) return false;
            if (level.getRaidAt(this.mason.blockPosition()) != null) return false;
            return this.remainingBuildTicks > 0 && this.buildOrigin != null;
        }

        @Override
        public void tick() {
            if (!(this.mason.level() instanceof ServerLevel level)) return;

            if (this.mason.blockPosition().distSqr(this.buildOrigin) > 36.0D) {
                if (this.mason.tickCount % 20 == 0) {
                    this.mason.getNavigation().moveTo(this.buildOrigin.getX() - 1.5D, this.buildOrigin.getY(), this.buildOrigin.getZ() - 1.5D, 0.7D);
                }
                return;
            }

            // Stand beside plot untradable, channeling the 2s/block timer
            this.mason.getNavigation().stop();
            this.mason.getLookControl().setLookAt(this.buildOrigin.getX() + 2.0D, this.buildOrigin.getY() + 1.0D, this.buildOrigin.getZ() + 2.0D);
            this.remainingBuildTicks--;

            if (this.mason.tickCount % 25 == 0) {
                this.mason.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, this.mason.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.NEUTRAL, 0.8F, 1.0F);
                level.sendParticles(ParticleTypes.CLOUD,
                        this.buildOrigin.getX() + 2.0D, this.buildOrigin.getY() + 0.5D, this.buildOrigin.getZ() + 2.0D,
                        6, 1.5D, 0.3D, 1.5D, 0.02D);
            }

            if (this.remainingBuildTicks <= 0) {
                this.pasteStructureWithBlending(level);
            }
        }

        private List<ResourceLocation> collectTemplatesFromVillagePack(ServerLevel level, BlockPos center) {
            Set<ResourceLocation> results = new LinkedHashSet<>();
            var structureRegistry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);

            // Inspect all structures overlapping the village center (detects custom village packs like CTOV, Terralith, Better Villages, etc.)
            var structuresAtCenter = level.structureManager().getAllStructuresAt(center);
            for (var structure : structuresAtCenter.keySet()) {
                ResourceLocation structId = structureRegistry.getKey(structure);
                if (structId == null) continue;

                String packNamespace = structId.getNamespace();
                String structPath = structId.getPath().toLowerCase(Locale.ROOT);

                // Extract biome/theme keyword from the village structure name (e.g. "plains", "taiga", "desert", "medieval")
                String themeKeyword = this.extractThemeKeyword(structPath, level, center);

                // Scan all house/building .nbt files inside THAT village pack's namespace
                var packResources = level.getServer().getResourceManager().listResources("structures",
                        loc -> loc.getNamespace().equals(packNamespace)
                                && loc.getPath().endsWith(".nbt")
                                && (loc.getPath().contains("/house") || loc.getPath().contains("/houses") || loc.getPath().contains("/building")));

                // First pass: templates in the same pack that match the village's biome/theme keyword
                for (ResourceLocation res : packResources.keySet()) {
                    String clean = res.getPath().substring("structures/".length(), res.getPath().length() - ".nbt".length());
                    if (clean.toLowerCase(Locale.ROOT).contains(themeKeyword)) {
                        results.add(new ResourceLocation(packNamespace, clean));
                    }
                }

                // Second pass: if no theme-specific match was found in that pack, include any house from that pack
                if (results.isEmpty()) {
                    for (ResourceLocation res : packResources.keySet()) {
                        String clean = res.getPath().substring("structures/".length(), res.getPath().length() - ".nbt".length());
                        results.add(new ResourceLocation(packNamespace, clean));
                    }
                }
            }

            // Fallback: if spawned via Spawn Egg outside a generated structure, use biome namespace or vanilla biome village
            if (results.isEmpty()) {
                String fallbackBiome = this.getVanillaBiomeFolder(level, center);
                var vanillaResources = level.getServer().getResourceManager().listResources(
                        "structures/village/" + fallbackBiome + "/houses",
                        loc -> loc.getPath().endsWith(".nbt"));

                for (ResourceLocation res : vanillaResources.keySet()) {
                    String clean = res.getPath().substring("structures/".length(), res.getPath().length() - ".nbt".length());
                    results.add(new ResourceLocation(res.getNamespace(), clean));
                }
            }

            return new ArrayList<>(results);
        }

        private String extractThemeKeyword(String structPath, ServerLevel level, BlockPos pos) {
            for (String theme : List.of("plains", "taiga", "desert", "savanna", "snowy", "jungle", "swamp", "badlands", "mesa", "cherry", "birch", "dark_forest")) {
                if (structPath.contains(theme)) {
                    return theme;
                }
            }
            return this.getVanillaBiomeFolder(level, pos);
        }
        private String getVanillaBiomeFolder(ServerLevel level, BlockPos pos) {
            var biomeHolder = level.getBiome(pos);
            if (biomeHolder.is(net.minecraft.tags.BiomeTags.HAS_VILLAGE_TAIGA)) return "taiga";
            if (biomeHolder.is(net.minecraft.tags.BiomeTags.HAS_VILLAGE_DESERT)) return "desert";
            if (biomeHolder.is(net.minecraft.tags.BiomeTags.HAS_VILLAGE_SAVANNA)) return "savanna";
            if (biomeHolder.is(net.minecraft.tags.BiomeTags.HAS_VILLAGE_SNOWY)) return "snowy";
            return "plains";
        }

        private List<BlockState> getTemplateBlockStates(ServerLevel level, StructureTemplate template) {
            List<BlockState> results = new ArrayList<>();
            CompoundTag nbt = template.save(new CompoundTag());
            net.minecraft.nbt.ListTag paletteTag = nbt.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
            net.minecraft.nbt.ListTag blocksTag = nbt.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);

            if (paletteTag.isEmpty() || blocksTag.isEmpty()) return results;

            List<BlockState> paletteStates = new ArrayList<>(paletteTag.size());
            var blockLookup = level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
            for (int i = 0; i < paletteTag.size(); i++) {
                paletteStates.add(net.minecraft.nbt.NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
            }
            for (int i = 0; i < blocksTag.size(); i++) {
                int stateIndex = blocksTag.getCompound(i).getInt("state");
                if (stateIndex >= 0 && stateIndex < paletteStates.size()) {
                    results.add(paletteStates.get(stateIndex));
                }
            }
            return results;
        }

        private Set<PoiType> detectWorkstationPoisInTemplate(ServerLevel level, ResourceLocation templateId) {
            Set<PoiType> detected = new HashSet<>();
            Optional<StructureTemplate> templateOpt = level.getStructureManager().get(templateId);
            if (templateOpt.isEmpty()) return detected;

            List<BlockState> states = this.getTemplateBlockStates(level, templateOpt.get());
            for (BlockState state : states) {
                if (state.isAir() || state.getBlock() instanceof BedBlock) continue;

                for (PoiType poiType : ForgeRegistries.POI_TYPES.getValues()) {
                    ResourceLocation poiKey = ForgeRegistries.POI_TYPES.getKey(poiType);
                    if (poiKey == null || poiKey.equals(PoiTypes.HOME.location()) || poiKey.equals(PoiTypes.MEETING.location())) {
                        continue;
                    }
                    if (poiType.matchingStates().contains(state)) {
                        detected.add(poiType);
                    }
                }
            }
            return detected;
        }

        @Nullable
        private ResourceLocation pickHouseTemplate(ServerLevel level, List templates) {
            List pureHouses = new ArrayList<>();
            for (Object id : templates) {
                if (this.detectWorkstationPoisInTemplate(level, (ResourceLocation) id).isEmpty()) {
                    pureHouses.add(id);
                }
            }
            List pool = pureHouses.isEmpty() ? templates : pureHouses;
            return (ResourceLocation) pool.get(level.random.nextInt(pool.size()));
        }
        @Nullable
        private ResourceLocation pickMissingPoiTemplate(ServerLevel level, BlockPos center, List packTemplates) {
            List tier1Food = new ArrayList<>();
            List tier2GuardProd = new ArrayList<>();
            List tier3Other = new ArrayList<>();
// Search the village's own pack first; fall back to vanilla biome folder if the pack lacks a specific POI
            List searchPool = new ArrayList<>(packTemplates);
            String fallbackBiome = this.getVanillaBiomeFolder(level, center);
            var vanillaResources = level.getServer().getResourceManager().listResources(
                    "structures/village/" + fallbackBiome + "/houses",
                    loc -> loc.getNamespace().equals("minecraft") && loc.getPath().endsWith(".nbt"));
            for (ResourceLocation res : vanillaResources.keySet()) {
                String clean = res.getPath().substring("structures/".length(), res.getPath().length() - ".nbt".length());
                ResourceLocation loc = new ResourceLocation("minecraft", clean);
                if (!searchPool.contains(loc)) searchPool.add(loc);
            }
            for (Object templateId : searchPool) {
                Set poisInTemplate = this.detectWorkstationPoisInTemplate(level, (ResourceLocation) templateId);
                if (poisInTemplate.isEmpty()) continue;
                for (Object poiType : poisInTemplate) {
                    long existingCount = level.getPoiManager().getCountInRange(
                            holder -> holder.value() == poiType, center, 48, PoiManager.Occupancy.ANY);
                    if (existingCount == 0) {
                        int priority = this.getPoiPriorityTier((PoiType) poiType);
                        if (priority == 1) tier1Food.add(templateId);
                        else if (priority == 2) tier2GuardProd.add(templateId);
                        else tier3Other.add(templateId);
                    }
                }
            }
            if (!tier1Food.isEmpty()) return (ResourceLocation) tier1Food.get(level.random.nextInt(tier1Food.size()));
            if (!tier2GuardProd.isEmpty()) return (ResourceLocation) tier2GuardProd.get(level.random.nextInt(tier2GuardProd.size()));
            if (!tier3Other.isEmpty()) return (ResourceLocation) tier3Other.get(level.random.nextInt(tier3Other.size()));
            return null;
        }
        private int getPoiPriorityTier(PoiType poiType) {
            ResourceLocation id = ForgeRegistries.POI_TYPES.getKey(poiType);
            if (id == null) return 3;
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (id.equals(PoiTypes.FARMER.location()) || id.equals(PoiTypes.BUTCHER.location()) || id.equals(PoiTypes.FISHERMAN.location())
                    || path.contains("farm") || path.contains("butcher") || path.contains("fish") || path.contains("cook") || path.contains("baker")) {
                return 1;
            }
            if (id.equals(PoiTypes.ARMORER.location()) || id.equals(PoiTypes.WEAPONSMITH.location()) || id.equals(PoiTypes.FLETCHER.location())
                    || id.equals(PoiTypes.CLERIC.location()) || id.equals(PoiTypes.TOOLSMITH.location())
                    || path.contains("smith") || path.contains("armor") || path.contains("weapon") || path.contains("fletch")
                    || path.contains("cleric") || path.contains("priest") || path.contains("guard") || path.contains("forge")) {
                return 2;
            }
            return 3;
        }
        @Nullable
        private BlockPos findValidPlot(ServerLevel level, BlockPos center, net.minecraft.core.Vec3i size)
        {
            int width = size.getX();
            int height = size.getY();
            int depth = size.getZ();

            // Collect bounding boxes of all already-generated village building pieces to prevent overlap
            List<net.minecraft.world.level.levelgen.structure.BoundingBox> existingPieces = new ArrayList<>();
            var structuresAtCenter = level.structureManager().getAllStructuresAt(center);
            for (var entry : structuresAtCenter.entrySet()) {
                var start = level.structureManager().getStructureAt(center, entry.getKey());
                if (start.isValid()) {
                    for (var piece : start.getPieces()) {
                        // Inflate by 2 blocks horizontally so new houses leave a clean walkway gap
                        existingPieces.add(piece.getBoundingBox().inflatedBy(2));
                    }
                }
            }

            for (int attempt = 0; attempt < 60; attempt++) {
                int x = center.getX() + level.random.nextInt(64) - 32;
                int z = center.getZ() + level.random.nextInt(64) - 32;
                int baseY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                int minY = baseY;
                int maxY = baseY;
                boolean validPlot = true;

                // 1. Check the X * Z footprint (plus 1-block road buffer) for flatness and valid ground
                for (int dx = -1; dx <= width && validPlot; dx++) {
                    for (int dz = -1; dz <= depth && validPlot; dz++) {
                        boolean insideFootprint = dx >= 0 && dx < width && dz >= 0 && dz < depth;
                        int checkY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);

                        if (insideFootprint) {
                            minY = Math.min(minY, checkY);
                            maxY = Math.max(maxY, checkY);
                        }

                        BlockPos groundPos = new BlockPos(x + dx, checkY - 1, z + dz);
                        BlockState groundState = level.getBlockState(groundPos);

                        // Reject water/lava, roads, or artificial blocks while accepting any modded dirt/sand/stone/snow
                        if (!groundState.getFluidState().isEmpty()
                                || groundState.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                                || groundState.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)
                                || groundState.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                                || !this.isNaturalTerrainBlock(groundState)) {
                            validPlot = false;
                            break;
                        }
                    }
                }

                if (!validPlot || (maxY - minY) > 2) {
                    continue;
                }

                // 2. Check if the full 3D structure hitbox (width x height x depth) intersects any existing village piece
                net.minecraft.world.level.levelgen.structure.BoundingBox candidateBox =
                        new net.minecraft.world.level.levelgen.structure.BoundingBox(
                                x, maxY, z,
                                x + width - 1, maxY + height - 1, z + depth - 1
                        );

                for (var pieceBox : existingPieces) {
                    if (candidateBox.intersects(pieceBox)) {
                        validPlot = false;
                        break;
                    }
                }
                if (!validPlot) continue;

                // 3. Verify the full X * Y * Z volume above ground contains no solid blocks (trees, walls, roofs)
                for (int dx = 0; dx < width && validPlot; dx++) {
                    for (int dz = 0; dz < depth && validPlot; dz++) {
                        for (int dy = 0; dy < height; dy++) {
                            BlockState volState = level.getBlockState(new BlockPos(x + dx, maxY + dy, z + dz));
                            if (!volState.isAir() && volState.isSolid()) {
                                validPlot = false;
                                break;
                            }
                        }
                    }
                }

                if (validPlot) {
                    return new BlockPos(x, maxY, z);
                }
            }
            return null;
        }

        private boolean isNaturalTerrainBlock(BlockState state) {
            // Uses Forge/Vanilla block tags so custom modded dirt, sand, stone, terracotta, and snow all work automatically
            return state.is(net.minecraft.tags.BlockTags.DIRT)
                    || state.is(net.minecraft.tags.BlockTags.SAND)
                    || state.is(net.minecraft.tags.BlockTags.SNOW)
                    || state.is(net.minecraft.tags.BlockTags.TERRACOTTA)
                    || state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD);
        }

        private void pasteStructureWithBlending(ServerLevel level) {
            Optional<StructureTemplate> templateOpt = level.getStructureManager().get(this.chosenTemplateId);
            if (templateOpt.isEmpty()) return;

            StructureTemplate template = templateOpt.get();
            net.minecraft.core.Vec3i size = template.getSize(this.chosenRotation);
            boolean isDesert = level.getBiome(this.buildOrigin).is(net.minecraft.tags.BiomeTags.HAS_VILLAGE_DESERT);

            // Sample actual terrain block underneath so custom modded ground blends naturally
            BlockPos samplePos = this.buildOrigin.below();
            BlockState sampledGround = level.getBlockState(samplePos);
            BlockState foundationBlock = sampledGround.isSolidRender(level, samplePos)
                    ? sampledGround
                    : (isDesert ? net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());

            // 1. Clear surface grass, flowers, and snow layers inside the exact footprint
            for (BlockPos clearPos : BlockPos.betweenClosed(
                    this.buildOrigin,
                    this.buildOrigin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
                BlockState existing = level.getBlockState(clearPos);
                if (!existing.isAir() && !existing.isSolid() && existing.getFluidState().isEmpty()) {
                    level.removeBlock(clearPos, false);
                }
            }

            // 2. Build downward foundation plinth beneath footprint so structure never floats on slopes
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos floorPos = this.buildOrigin.offset(x, -1, z);
                    for (int depth = 0; depth < 8; depth++) {
                        BlockPos supportPos = floorPos.below(depth);
                        if (level.getBlockState(supportPos).isSolid()) break;
                        level.setBlock(supportPos, foundationBlock, 3);
                    }
                }
            }

            // 3. Adjust anchor for rotation so rotated houses stay strictly inside [buildOrigin, buildOrigin + size]
            BlockPos transformedOrigin = StructureTemplate.getZeroPositionWithTransform(
                    this.buildOrigin,
                    net.minecraft.world.level.block.Mirror.NONE,
                    this.chosenRotation,
                    size.getX(),
                    size.getZ()
            );

            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings =
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                            .setRotation(this.chosenRotation)
                            .addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor.INSTANCE)
                            .addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor.STRUCTURE_AND_AIR);

            template.placeInWorld(level, transformedOrigin, transformedOrigin, settings, level.random, 2);

            // 4. Connect the newly placed building to the nearest village road
            this.connectToNearestRoad(level, size, isDesert);

            level.playSound(null, this.buildOrigin, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.2F, 1.0F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    this.buildOrigin.getX() + size.getX() / 2.0D,
                    this.buildOrigin.getY() + 2.0D,
                    this.buildOrigin.getZ() + size.getZ() / 2.0D,
                    15, 2.0D, 1.5D, 2.0D, 0.0D);
        }

        private void connectToNearestRoad(ServerLevel level, net.minecraft.core.Vec3i size, boolean isDesert) {
            BlockPos startPoint = this.buildOrigin.offset(size.getX() / 2, 0, size.getZ() / 2);
            for (BlockPos pos : BlockPos.betweenClosed(this.buildOrigin, this.buildOrigin.offset(size.getX(), 2, size.getZ()))) {
                if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                    startPoint = pos.immutable();
                    break;
                }
            }
            BlockPos nearestRoad = null;
            double bestDistSq = 256.0D;
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    int wx = startPoint.getX() + dx;
                    int wz = startPoint.getZ() + dz;
                    if (wx >= this.buildOrigin.getX() && wx <= this.buildOrigin.getX() + size.getX()
                            && wz >= this.buildOrigin.getZ() && wz <= this.buildOrigin.getZ() + size.getZ()) {
                        continue;
                    }
                    int wy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                    BlockPos check = new BlockPos(wx, wy, wz);
                    BlockState state = level.getBlockState(check);
                    if (state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || state.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)) {
                        double distSq = check.distSqr(startPoint);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            nearestRoad = check;
                        }
                    }
                }
            }
            if (nearestRoad == null) return;
            BlockState pathState = isDesert
                    ? net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
            int curX = startPoint.getX();
            int curZ = startPoint.getZ();
            int targetX = nearestRoad.getX();
            int targetZ = nearestRoad.getZ();
            while (curX != targetX || curZ != targetZ) {
                if (curX != targetX) {
                    curX += Integer.compare(targetX, curX);
                } else {
                    curZ += Integer.compare(targetZ, curZ);
                }
                int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, curX, curZ) - 1;
                BlockPos groundPos = new BlockPos(curX, surfaceY, curZ);
                BlockState ground = level.getBlockState(groundPos);
                if (ground.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                        || ground.is(net.minecraft.world.level.block.Blocks.DIRT)
                        || ground.is(net.minecraft.world.level.block.Blocks.SAND)) {
                    level.setBlock(groundPos, pathState, 3);
                }
            }
        }
        @Override
        public void stop() {
            this.mason.getPersistentData().remove("IsConstructing");
            this.mason.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.remainingBuildTicks = 0;
        }
    }

    private static class StayNearVillageAnchorGoal extends Goal {
        private final Villager villager;
        private BlockPos closestAnchor;
        private int checkCooldown = 40;

        public StayNearVillageAnchorGoal(Villager villager) {
            this.villager = villager;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!(this.villager.level() instanceof ServerLevel level) || this.villager.isBaby()) {
                return false;
            }
            // Don't interfere with active raids, sermons, or village abandonment migration
            if (level.getRaidAt(this.villager.blockPosition()) != null
                    || this.villager.getPersistentData().getBoolean("IsConstructing")
                    || this.villager.getPersistentData().getBoolean("IsEducatingNitwit")) {
                return false;
            }
            if (--this.checkCooldown > 0) {
                return false;
            }
            this.checkCooldown = 40; // Check every 2 seconds

            this.closestAnchor = this.findClosestAnchor(level);
            if (this.closestAnchor == null) {
                return false;
            }

            // Trigger if the villager drifted more than 20 blocks away from their nearest anchor
            return this.villager.blockPosition().distSqr(this.closestAnchor) > 400.0D;
        }

        @Override
        public boolean canContinueToUse() {
            if (this.closestAnchor == null || this.villager.getNavigation().isDone()) {
                return false;
            }
            // Stop once back within 10 blocks of their home, workplace, or bell
            return this.villager.blockPosition().distSqr(this.closestAnchor) > 100.0D;
        }

        @Override
        public void start() {
            if (this.closestAnchor != null) {
                this.villager.getNavigation().moveTo(
                        this.closestAnchor.getX() + 0.5D,
                        this.closestAnchor.getY(),
                        this.closestAnchor.getZ() + 0.5D,
                        0.55D
                );
            }
        }

        @Nullable
        private BlockPos findClosestAnchor(ServerLevel level) {
            BlockPos currentPos = this.villager.blockPosition();
            List<BlockPos> anchors = new ArrayList<>();

            BlockPos ownedBed = VillageBedSavedData.get(level).getOwnedBed(this.villager.getUUID());
            if (ownedBed != null) {
                anchors.add(ownedBed);
            }

            this.villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).ifPresent(globalPos -> {
                if (globalPos.dimension() == level.dimension()) {
                    anchors.add(globalPos.pos());
                }
            });

            BlockPos center = VillagerLifeHandler.getVillageCenter(level, currentPos);
            anchors.add(center);

            anchors.sort(Comparator.comparingDouble(pos -> pos.distSqr(currentPos)));
            return anchors.isEmpty() ? null : anchors.get(0);
        }
    }
}
