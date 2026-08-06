package cn.blindboxchallenge.entity;

import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.registry.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** 008、016 共用的受同步抱枕变体；数值稳定保存到实体 NBT。 */
public enum PillowVariant {
    STONE(0, 3.0F),
    DIAMOND(1, 5.0F);

    private final int serializedId;
    private final float impactDamage;

    PillowVariant(int serializedId, float impactDamage) {
        this.serializedId = serializedId;
        this.impactDamage = impactDamage;
    }

    public int serializedId() {
        return serializedId;
    }

    public float impactDamage() {
        return impactDamage;
    }

    public Item item() {
        return this == DIAMOND ? ModItems.DIAMOND_PILLOW.get() : ModItems.STONE_PILLOW.get();
    }

    public ItemStack createStack() {
        return new ItemStack(item());
    }

    public static PillowVariant fromSerializedId(int serializedId) {
        return serializedId == DIAMOND.serializedId ? DIAMOND : STONE;
    }

    public static PillowVariant fromItem(ItemStack stack) {
        return stack.is(ModItems.DIAMOND_PILLOW.get()) ? DIAMOND : STONE;
    }

    public static PillowVariant fromBlock(Block block) {
        return block == ModBlocks.DIAMOND_PILLOW.get() ? DIAMOND : STONE;
    }
}
