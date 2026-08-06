package cn.blindboxchallenge.blockentity;

import cn.blindboxchallenge.registry.ModBlockEntities;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 门本地持久状态只保存 UUID 与全局位置；加载损坏字段会被视为未配对。 */
public final class AnywhereDoorBlockEntity extends BlockEntity {
    private static final String DOOR_ID = "door_id";
    private static final String PARTNER_ID = "partner_id";
    private static final String PARTNER_DOOR = "partner_door";
    private static final String DESTINATION_SAFETY = "destination_safety";
    private UUID doorId = UUID.randomUUID();
    private UUID partnerDoorId;
    private GlobalPos partnerDoor;
    private GlobalPos destinationSafety;

    public AnywhereDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANYWHERE_DOOR.get(), pos, state);
    }

    public UUID doorId() { return doorId; }
    public Optional<UUID> partnerDoorId() { return Optional.ofNullable(partnerDoorId); }
    public Optional<GlobalPos> partnerDoor() { return Optional.ofNullable(partnerDoor); }
    public Optional<GlobalPos> destinationSafety() { return Optional.ofNullable(destinationSafety); }
    public boolean linked() { return partnerDoorId != null && partnerDoor != null && destinationSafety != null; }

    public void link(UUID partnerId, GlobalPos partner, GlobalPos safety) {
        this.partnerDoorId = partnerId;
        this.partnerDoor = partner;
        this.destinationSafety = safety;
        setChanged();
    }

    public void clearLink() {
        if (partnerDoorId == null && partnerDoor == null && destinationSafety == null) return;
        partnerDoorId = null;
        partnerDoor = null;
        destinationSafety = null;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID(DOOR_ID, doorId);
        if (partnerDoorId != null) tag.putUUID(PARTNER_ID, partnerDoorId);
        writeGlobal(tag, PARTNER_DOOR, partnerDoor);
        writeGlobal(tag, DESTINATION_SAFETY, destinationSafety);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        doorId = tag.hasUUID(DOOR_ID) ? tag.getUUID(DOOR_ID) : UUID.randomUUID();
        partnerDoorId = tag.hasUUID(PARTNER_ID) ? tag.getUUID(PARTNER_ID) : null;
        partnerDoor = readGlobal(tag, PARTNER_DOOR).orElse(null);
        destinationSafety = readGlobal(tag, DESTINATION_SAFETY).orElse(null);
        if (!linked()) clearLink();
    }

    private static void writeGlobal(CompoundTag tag, String key, GlobalPos globalPos) {
        if (globalPos == null) return;
        CompoundTag value = new CompoundTag();
        value.putString("dimension", globalPos.dimension().location().toString());
        value.putLong("position", globalPos.pos().asLong());
        tag.put(key, value);
    }

    private static Optional<GlobalPos> readGlobal(CompoundTag tag, String key) {
        if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag value = tag.getCompound(key);
        if (!value.contains("dimension", net.minecraft.nbt.Tag.TAG_STRING) || !value.contains("position", net.minecraft.nbt.Tag.TAG_LONG)) return Optional.empty();
        try {
            ResourceLocation id = new ResourceLocation(value.getString("dimension"));
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
            return Optional.of(GlobalPos.of(dimension, BlockPos.of(value.getLong("position"))));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
