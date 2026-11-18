package cursed.polemy.core.events;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

public class VillageBedSavedData extends SavedData {
    private static final String DATA_NAME = "polemy_village_beds";

    // Maps bed head BlockPos -> Villager UUID
    private final Map<BlockPos, UUID> villagerBeds = new HashMap<>();
    // Maps Player UUID -> Single last-slept bed head BlockPos
    private final Map<UUID, BlockPos> playerBeds = new HashMap<>();

    public static VillageBedSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VillageBedSavedData::load,
                VillageBedSavedData::new,
                DATA_NAME
        );
    }

    public boolean isBedAvailable(ServerLevel level, BlockPos bedPos, UUID requesterId) {
        // Check if any player currently claims this exact bed as their active spawn/sleep bed
        if (this.playerBeds.containsValue(bedPos)) {
            return false;
        }

        UUID ownerId = this.villagerBeds.get(bedPos);
        if (ownerId == null || ownerId.equals(requesterId)) {
            return true;
        }

        Entity owner = level.getEntity(ownerId);
        if (owner instanceof Villager villager && villager.isAlive()) {
            return false;
        }

        this.villagerBeds.remove(bedPos);
        this.setDirty();
        return true;
    }

    public void claimBedForVillager(BlockPos bedPos, UUID villagerId) {
        this.villagerBeds.entrySet().removeIf(entry -> entry.getValue().equals(villagerId));
        this.villagerBeds.put(bedPos.immutable(), villagerId);
        this.setDirty();
    }

    // Overwrites the player's previous bed so they only ever hold 1 bed at a time
    public void claimBedForPlayer(UUID playerId, BlockPos bedPos) {
        BlockPos normalized = bedPos.immutable();
        this.villagerBeds.remove(normalized);
        this.playerBeds.put(playerId, normalized);
        this.setDirty();
    }

    @Nullable
    public BlockPos getOwnedBed(UUID villagerId) {
        for (Map.Entry<BlockPos, UUID> entry : this.villagerBeds.entrySet()) {
            if (entry.getValue().equals(villagerId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static VillageBedSavedData load(CompoundTag tag) {
        VillageBedSavedData data = new VillageBedSavedData();

        ListTag villagerList = tag.getList("VillagerBeds", Tag.TAG_COMPOUND);
        for (int i = 0; i < villagerList.size(); i++) {
            CompoundTag entry = villagerList.getCompound(i);
            BlockPos pos = NbtUtils.readBlockPos(entry.getCompound("Pos"));
            UUID uuid = entry.getUUID("Owner");
            data.villagerBeds.put(pos, uuid);
        }

        ListTag playerList = tag.getList("PlayerBeds", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag entry = playerList.getCompound(i);
            UUID playerId = entry.getUUID("Player");
            BlockPos pos = NbtUtils.readBlockPos(entry.getCompound("Pos"));
            data.playerBeds.put(playerId, pos);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag villagerList = new ListTag();
        for (Map.Entry<BlockPos, UUID> entry : this.villagerBeds.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.put("Pos", NbtUtils.writeBlockPos(entry.getKey()));
            item.putUUID("Owner", entry.getValue());
            villagerList.add(item);
        }
        tag.put("VillagerBeds", villagerList);

        ListTag playerList = new ListTag();
        for (Map.Entry<UUID, BlockPos> entry : this.playerBeds.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("Player", entry.getKey());
            item.put("Pos", NbtUtils.writeBlockPos(entry.getValue()));
            playerList.add(item);
        }
        tag.put("PlayerBeds", playerList);

        return tag;
    }

    @Nullable
    public UUID releaseBed(BlockPos bedPos) {
        UUID previousOwner = this.villagerBeds.remove(bedPos);
        boolean removedPlayer = this.playerBeds.entrySet().removeIf(entry -> entry.getValue().equals(bedPos));

        if (previousOwner != null || removedPlayer) {
            this.setDirty();
        }
        return previousOwner;
    }

}
