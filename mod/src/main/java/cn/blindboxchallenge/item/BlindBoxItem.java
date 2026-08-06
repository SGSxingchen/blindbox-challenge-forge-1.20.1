package cn.blindboxchallenge.item;

import cn.blindboxchallenge.service.BlindBoxService;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** 长按开盒；所有奖池变更仅发生在 finishUsingItem 的服务端分支。 */
public final class BlindBoxItem extends Item {
    private static final UUID USING_SLOW_UUID = UUID.fromString("8d1ebffa-f885-45aa-a8df-99cd8e039ac1");
    private static final String USING_KEY = "blindboxchallenge_opening";
    private static final int USE_TICKS = 40;

    public BlindBoxItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlindBoxService.ensureToken(stack);
            stack.getOrCreateTag().putBoolean(USING_KEY, true);
            applySlow(serverPlayer);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack) { return USE_TICKS; }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.BOW; }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            clearUsing(player, stack);
            BlindBoxService.open(player, stack);
        }
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) clearUsing(player, stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if (!level.isClientSide && entity instanceof ServerPlayer player && stack.hasTag() && stack.getTag().getBoolean(USING_KEY) && !player.isUsingItem()) {
            clearUsing(player, stack);
        }
    }

    public static void cancelUse(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(USING_SLOW_UUID);
        for (ItemStack stack : player.getInventory().items) if (stack.getItem() instanceof BlindBoxItem && stack.hasTag()) stack.getTag().remove(USING_KEY);
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof BlindBoxItem && offhand.hasTag()) offhand.getTag().remove(USING_KEY);
    }

    /** 隔离 CI 探针只读取生命周期状态，不暴露修改入口。 */
    public static boolean hasActiveUseState(ServerPlayer player, ItemStack stack) {
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        return stack.hasTag() && stack.getTag().getBoolean(USING_KEY)
                && attribute != null && attribute.getModifier(USING_SLOW_UUID) != null;
    }

    private static void applySlow(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null && attribute.getModifier(USING_SLOW_UUID) == null) {
            attribute.addTransientModifier(new AttributeModifier(USING_SLOW_UUID, "blindboxchallenge.opening_slow", -0.6D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void clearUsing(ServerPlayer player, ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(USING_KEY);
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(USING_SLOW_UUID);
    }
}
