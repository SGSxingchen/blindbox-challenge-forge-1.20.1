package cn.blindboxchallenge.item;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;

/** 002：一个 ItemStack 内切换木镐与木剑语义的紫色玩具工具。 */
public final class PurpleToyPickaxeSwordItem extends Item {
    public static final String PICKAXE_FORM_KEY = "PickaxeForm";

    public PurpleToyPickaxeSwordItem() {
        super(new Item.Properties().durability(Tiers.WOOD.getUses()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 不在客户端预测切换：服务端 NBT 更新由原版手持栈同步给观察者和持有者。
        if (!level.isClientSide) setPickaxeForm(stack, !isPickaxeForm(stack));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ToolAction action) {
        return isPickaxeForm(stack) ? ToolActions.DEFAULT_PICKAXE_ACTIONS.contains(action)
                : ToolActions.DEFAULT_SWORD_ACTIONS.contains(action);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        if (isPickaxeForm(stack)) return state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? Tiers.WOOD.getSpeed() : 1.0F;
        return state.is(Blocks.COBWEB) ? 15.0F : 1.5F;
    }

    @Override
    public boolean isCorrectToolForDrops(BlockState state) {
        return isWoodPickaxeCorrectForDrops(state);
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        return isPickaxeForm(stack) && isWoodPickaxeCorrectForDrops(state);
    }

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
        if (!level.isClientSide && state.getDestroySpeed(level, pos) != 0.0F) {
            stack.hurtAndBreak(isPickaxeForm(stack) ? 1 : 2, miner, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        return true;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        return true;
    }

    @Override
    public int getEnchantmentValue() {
        return Tiers.WOOD.getEnchantmentValue();
    }

    /** 默认是镐；未知/损坏 NBT 也回退到该保守默认形态。 */
    public static boolean isPickaxeForm(ItemStack stack) {
        return !stack.hasTag() || !stack.getTag().contains(PICKAXE_FORM_KEY, Tag.TAG_BYTE)
                || stack.getTag().getBoolean(PICKAXE_FORM_KEY);
    }

    /** 仅由服务端右键入口写入；客户端模型仅只读这个已同步字段。 */
    public static void setPickaxeForm(ItemStack stack, boolean pickaxeForm) {
        stack.getOrCreateTag().putBoolean(PICKAXE_FORM_KEY, pickaxeForm);
    }

    private static boolean isWoodPickaxeCorrectForDrops(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                && !state.is(BlockTags.NEEDS_STONE_TOOL)
                && !state.is(BlockTags.NEEDS_IRON_TOOL)
                && !state.is(BlockTags.NEEDS_DIAMOND_TOOL);
    }
}
