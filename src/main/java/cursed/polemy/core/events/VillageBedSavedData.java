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

    // Maps bed head BlockPos -> Owner UUID (Villager UUID)
    private final Map<BlockPos, UUID> villagerBeds = new HashMap<>();
    // Beds claimed permanently by players sleeping in them
    private final Set<BlockPos> playerClaimedBeds = new HashSet<>();

    public static VillageBedSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VillageBedSavedData::load,
                VillageBedSavedData::new,
                DATA_NAME
        );
    }

    public boolean isBedAvailable(ServerLevel level, BlockPos bedPos, UUID requesterId) {
        if (this.playerClaimedBeds.contains(bedPos)) {
            return false; // Permanently occupied by a player
        }
        UUID ownerId = this.villagerBeds.get(bedPos);
        if (ownerId == null || ownerId.equals(requesterId)) {
            return true;
        }
        // Check if the previous owner is still alive
        Entity owner = level.getEntity(ownerId);
        if (owner instanceof Villager villager && villager.isAlive()) {
            return false;
        }
        // Owner died -> free the bed
        this.villagerBeds.remove(bedPos);
        this.setDirty();
        return true;
    }

    public void claimBedForVillager(BlockPos bedPos, UUID villagerId) {
        // Remove any old bed owned by this villager
        this.villagerBeds.entrySet().removeIf(entry -> entry.getValue().equals(villagerId));
        this.villagerBeds.put(bedPos.immutable(), villagerId);
        this.setDirty();
    }

    public void claimBedForPlayer(BlockPos bedPos) {
        this.villagerBeds.remove(bedPos);
        this.playerClaimedBeds.add(bedPos.immutable());
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
            data.playerClaimedBeds.add(NbtUtils.readBlockPos(playerList.getCompound(i)));
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
        for (BlockPos pos : this.playerClaimedBeds) {
            playerList.add(NbtUtils.writeBlockPos(pos));
        }
        tag.put("PlayerBeds", playerList);
        return tag;
    }
}
