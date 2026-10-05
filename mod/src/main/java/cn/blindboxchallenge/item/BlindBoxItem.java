package cn.blindboxchallenge.item;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.service.BlindBoxService;
import java.util.UUID;
import net.minecraft.nbt.Tag;
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
    private static final String OPENING_DURATION_KEY = "blindboxchallenge_opening_duration";
    private static final String OPENING_START_TICK_KEY = "blindboxchallenge_opening_start_tick";

    public BlindBoxItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isUsingItem()) return InteractionResultHolder.consume(stack);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlindBoxService.ensureToken(stack);
            stack.getOrCreateTag().putBoolean(USING_KEY, true);
            // 只在开始时写一次快照；原版倒计时与开盖动画都不受中途配置变化影响。
            stack.getOrCreateTag().putInt(OPENING_DURATION_KEY, configuredOpeningTicks());
            stack.getOrCreateTag().putLong(OPENING_START_TICK_KEY, level.getGameTime());
            applySlow(serverPlayer);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack) { return openingDuration(stack); }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    /** 两端只读的本次开启时长；未开始使用时才读取配置。 */
    public static int openingDuration(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().getBoolean(USING_KEY)
                && stack.getTag().contains(OPENING_DURATION_KEY, Tag.TAG_INT)) {
            int duration = stack.getTag().getInt(OPENING_DURATION_KEY);
            if (duration >= 10 && duration <= 200) return duration;
        }
        return configuredOpeningTicks();
    }

    /** 正式使用态由服务端写入；客户端按世界时间呈现动画，不触碰抽奖业务。 */
    public static long openingStartTick(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(USING_KEY)
                && stack.getTag().contains(OPENING_START_TICK_KEY, Tag.TAG_LONG)
                ? stack.getTag().getLong(OPENING_START_TICK_KEY) : -1L;
    }

    /** 仅显示当前使用栈的服务端快照；缺失快照或无实体的物品栏预览一律闭盖。 */
    public static float getOpeningProgress(ItemStack stack, LivingEntity entity) {
        return getOpeningProgress(stack, entity, 0.0F);
    }

    /** 帧间进度只用于客户端显示，服务端仍按完整游戏刻结算。 */
    public static float getOpeningProgress(ItemStack stack, LivingEntity entity, float partialTick) {
        if (entity == null || !entity.isUsingItem() || entity.getUseItem() != stack) return 0.0F;
        long startTick = openingStartTick(stack);
        if (startTick < 0L || !stack.getTag().contains(OPENING_DURATION_KEY, Tag.TAG_INT)) return 0.0F;
        int duration = stack.getTag().getInt(OPENING_DURATION_KEY);
        if (duration < 10 || duration > 200) return 0.0F;
        float frame = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        double elapsedTicks = (double) entity.level().getGameTime() - startTick + frame;
        return (float) Math.max(0.0D, Math.min(1.0D, elapsedTicks / duration));
    }

    private static int configuredOpeningTicks() {
        // 资源预览可能发生在客户端尚未收到 SERVER 配置之前，使用既有默认值。
        return ModServerConfig.SERVER_SPEC.isLoaded() ? ModServerConfig.BLIND_BOX_OPENING_TICKS.get() : 40;
    }

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
        for (ItemStack stack : player.getInventory().items) if (stack.getItem() instanceof BlindBoxItem) clearUsingTags(stack);
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof BlindBoxItem) clearUsingTags(offhand);
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
        clearUsingTags(stack);
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(USING_SLOW_UUID);
    }

    private static void clearUsingTags(ItemStack stack) {
        if (!stack.hasTag()) return;
        stack.getTag().remove(USING_KEY);
        stack.getTag().remove(OPENING_DURATION_KEY);
        stack.getTag().remove(OPENING_START_TICK_KEY);
    }
}
