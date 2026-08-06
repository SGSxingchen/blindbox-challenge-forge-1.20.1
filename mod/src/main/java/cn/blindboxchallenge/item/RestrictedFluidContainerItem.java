package cn.blindboxchallenge.item;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.ForgeEventFactory;

/**
 * 受限液体容器的公共实现。
 *
 * <p>不调用 {@link BucketItem#use}：原版填充成功后会用另一个原版桶替换当前
 * ItemStack，因而会丢失本模组容器的耐久和白名单状态。液体种类保存在当前
 * ItemStack 的 NBT 中；所有取放世界液体的实际写入只在逻辑服务端执行。</p>
 */
public abstract class RestrictedFluidContainerItem extends Item {
    private static final String CONTAINED_FLUID_KEY = "ContainedFluid";

    protected RestrictedFluidContainerItem(Properties properties) {
        // 物品耐久会自行锁定为不可堆叠；不得在 durability() 之后再次 stacksTo()，
        // 否则 Forge 1.20.1 会在注册期抛出“Unable to have damage AND stack”。
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Fluid contained = getContainedFluid(stack);
        BlockHitResult hit = getPlayerPOVHitResult(level, player,
                contained == Fluids.EMPTY ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
        var hookResult = ForgeEventFactory.onBucketUse(player, level, stack, hit);
        if (hookResult != null) return hookResult;
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.fail(stack);

        BlockPos hitPos = hit.getBlockPos();
        Direction direction = hit.getDirection();
        if (contained == Fluids.EMPTY) {
            return fillFromSource(level, player, hand, stack, hitPos, direction);
        }
        return emptyIntoWorld(level, player, stack, contained, hit, hitPos, direction);
    }

    private InteractionResultHolder<ItemStack> fillFromSource(Level level, Player player, InteractionHand hand,
                                                                ItemStack stack, BlockPos pos, Direction direction) {
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, direction, stack)) {
            return InteractionResultHolder.fail(stack);
        }
        BlockState state = level.getBlockState(pos);
        Fluid sourceFluid = state.getFluidState().getType();
        if (!state.getFluidState().isSource() || !accepts(sourceFluid) || !(state.getBlock() instanceof BucketPickup pickup)) {
            return InteractionResultHolder.fail(stack);
        }
        // 由服务端拾取源方块；客户端只返回成功以发送原版使用请求，绝不预测改写世界或 NBT。
        if (!level.isClientSide) {
            ItemStack pickedUp = pickup.pickupBlock(level, pos, state);
            if (pickedUp.isEmpty()) return InteractionResultHolder.fail(stack);
            player.awardStat(Stats.ITEM_USED.get(this));
            pickup.getPickupSound(state).ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
            level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
            if (damagesOnPickup(sourceFluid)) {
                stack.hurtAndBreak(1, player, entity -> entity.broadcastBreakEvent(hand));
            }
            if (!stack.isEmpty()) setContainedFluid(stack, sourceFluid);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private InteractionResultHolder<ItemStack> emptyIntoWorld(Level level, Player player, ItemStack stack, Fluid fluid,
                                                               BlockHitResult hit, BlockPos hitPos, Direction direction) {
        BlockState hitState = level.getBlockState(hitPos);
        BlockPos placementPos = canBlockContainFluid(level, hitPos, hitState, fluid) ? hitPos : hitPos.relative(direction);
        if (!level.mayInteract(player, hitPos) || !player.mayUseItemAt(placementPos, direction, stack)) {
            return InteractionResultHolder.fail(stack);
        }
        // emptyContents 复用原版的可替换方块、含水方块、下界蒸发和事件语义；不会替换本 ItemStack。
        if (!level.isClientSide) {
            if (!emptyContents(fluid, player, level, placementPos, hit, stack)) {
                return InteractionResultHolder.fail(stack);
            }
            player.awardStat(Stats.ITEM_USED.get(this));
            if (!player.getAbilities().instabuild) clearContainedFluid(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static boolean canBlockContainFluid(Level level, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getBlock() instanceof LiquidBlockContainer container && container.canPlaceLiquid(level, pos, state, fluid);
    }

    private static boolean emptyContents(Fluid fluid, @Nullable Player player, Level level, BlockPos pos,
                                         @Nullable BlockHitResult hit, ItemStack container) {
        BucketItem vanillaBucket = fluid == Fluids.WATER ? (BucketItem) Items.WATER_BUCKET : (BucketItem) Items.LAVA_BUCKET;
        return vanillaBucket.emptyContents(player, level, pos, hit, container);
    }

    /** 供服务端探针和模型谓词读取；未知/损坏 NBT 一律按空容器处理。 */
    public static Fluid getContainedFluid(ItemStack stack) {
        if (stack.hasTag()) {
            String id = stack.getTag().getString(CONTAINED_FLUID_KEY);
            if ("minecraft:water".equals(id)) return Fluids.WATER;
            if ("minecraft:lava".equals(id)) return Fluids.LAVA;
        }
        return Fluids.EMPTY;
    }

    private static void setContainedFluid(ItemStack stack, Fluid fluid) {
        String id = fluid == Fluids.WATER ? "minecraft:water" : "minecraft:lava";
        stack.getOrCreateTag().putString(CONTAINED_FLUID_KEY, id);
    }

    private static void clearContainedFluid(ItemStack stack) {
        if (!stack.hasTag()) return;
        stack.getTag().remove(CONTAINED_FLUID_KEY);
        if (stack.getTag().isEmpty()) stack.setTag(null);
    }

    protected abstract boolean accepts(Fluid fluid);

    protected boolean damagesOnPickup(Fluid fluid) {
        return false;
    }
}
