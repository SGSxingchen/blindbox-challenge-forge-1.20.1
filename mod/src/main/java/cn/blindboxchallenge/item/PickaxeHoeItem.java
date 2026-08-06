package cn.blindboxchallenge.item;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;

/** 019：铁镐采掘能力与铁锄耕地交互合并在同一物品。 */
public final class PickaxeHoeItem extends HoeItem {
    public PickaxeHoeItem() {
        super(Tiers.IRON, -2, -1.0F, new Item.Properties().durability(Tiers.IRON.getUses()));
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ToolAction action) {
        return ToolActions.DEFAULT_PICKAXE_ACTIONS.contains(action) || super.canPerformAction(stack, action);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ? Tiers.IRON.getSpeed() : super.getDestroySpeed(stack, state);
    }

    @Override
    public boolean isCorrectToolForDrops(BlockState state) {
        if (!state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return super.isCorrectToolForDrops(state);
        return Tiers.IRON.getLevel() >= 3 || (!state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)
                && (Tiers.IRON.getLevel() >= 2 || !state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL))
                && (Tiers.IRON.getLevel() >= 1 || !state.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL)));
    }
}
