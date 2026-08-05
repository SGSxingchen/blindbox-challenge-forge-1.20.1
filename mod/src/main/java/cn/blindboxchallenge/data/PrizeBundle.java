package cn.blindboxchallenge.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** 全局奖池中的不可变奖项；每个元素保留完整 ItemStack NBT。 */
public record PrizeBundle(UUID id, UUID creator, long createdGameTime, long version, List<ItemStack> stacks) {
    public PrizeBundle {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) copies.add(stack.copy());
        stacks = List.copyOf(copies);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("creator", creator);
        tag.putLong("created_game_time", createdGameTime);
        tag.putLong("version", version);
        ListTag values = new ListTag();
        for (ItemStack stack : stacks) values.add(stack.save(new CompoundTag()));
        tag.put("stacks", values);
        return tag;
    }

    public static PrizeBundle load(CompoundTag tag) {
        List<ItemStack> stacks = new ArrayList<>();
        ListTag values = tag.getList("stacks", Tag.TAG_COMPOUND);
        for (int i = 0; i < values.size(); i++) {
            ItemStack stack = ItemStack.of(values.getCompound(i));
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return new PrizeBundle(tag.getUUID("id"), tag.getUUID("creator"), tag.getLong("created_game_time"), tag.getLong("version"), stacks);
    }
}
