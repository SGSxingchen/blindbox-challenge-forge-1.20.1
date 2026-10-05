package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.registry.ModItems;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.common.util.FakePlayer;

/** 专服探针：模拟玩家逐刻调用原版使用倒计时，不冒充真实客户端操作。 */
final class BlindBoxOpeningCiAssertions {
    static int run(CommandSourceStack source) {
        var pool = BlindBoxPoolSavedData.get(source.getServer().overworld());
        if (!pool.bundles().isEmpty() || !pool.transactions().isEmpty()) {
            source.sendFailure(Component.literal("开盒探针要求空奖池与空事务的隔离世界"));
            return 0;
        }
        int original = ModServerConfig.BLIND_BOX_OPENING_TICKS.get();
        OpeningPlayer player = new OpeningPlayer(source.getServer().overworld());
        ItemStack box = new ItemStack(ModItems.BLIND_BOX.get());
        try {
            ModServerConfig.BLIND_BOX_OPENING_TICKS.set(80);
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            box.use(player.level(), player, InteractionHand.MAIN_HAND);
            require(box.getUseDuration() == 80 && player.getUseItemRemainingTicks() == 80,
                    "80刻配置未进入真实使用倒计时");
            require(BlindBoxItem.hasActiveUseState(player, box) && box.getUseAnimation() == UseAnim.NONE,
                    "开启状态、减速或姿势异常");
            long start = BlindBoxItem.openingStartTick(box);
            for (int tick = 0; tick < 40; tick++) player.advanceOpening();
            ModServerConfig.BLIND_BOX_OPENING_TICKS.set(10);
            box.use(player.level(), player, InteractionHand.MAIN_HAND);
            require(box.getUseDuration() == 80 && player.getUseItemRemainingTicks() == 40
                    && BlindBoxItem.openingStartTick(box) == start, "改配置或重复使用重置了本次开启");
            require(BlindBoxItem.getOpeningProgress(box.copy(), player) == 0.0F
                    && BlindBoxItem.getOpeningProgress(box, null) == 0.0F, "非活跃栈或空实体触发动画");
            long now = player.level().getGameTime();
            require(now > 0L, "探针须在世界开始运行后执行");
            box.getTag().putLong("blindboxchallenge_opening_start_tick", 0L);
            require(BlindBoxItem.getOpeningProgress(box, player) == (float) Math.min(1.0D, now / 80.0D),
                    "活跃盒盖未按服务端快照推进或超过上限");
            box.getTag().putLong("blindboxchallenge_opening_start_tick", player.level().getGameTime() + 5);
            require(BlindBoxItem.getOpeningProgress(box, player) == 0.0F, "未来开始时间未限制为零");
            box.getTag().putLong("blindboxchallenge_opening_start_tick", start);
            for (int tick = 0; tick < 39; tick++) player.advanceOpening();
            require(player.isUsingItem() && player.getUseItemRemainingTicks() == 1, "未满80刻提前结算");
            player.advanceOpening();
            require(!player.isUsingItem() && box.getCount() == 1
                    && !BlindBoxItem.hasActiveUseState(player, box)
                    && BlindBoxItem.openingStartTick(box) == -1L, "空池完成后消耗或使用状态残留");

            // 第二次从新配置开始，实际 releaseUsingItem 路径必须清掉三项使用态和减速。
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, box);
            box.use(player.level(), player, InteractionHand.OFF_HAND);
            require(player.getUseItemRemainingTicks() == 10, "下一次开启没有使用新配置");
            player.advanceOpening();
            player.releaseUsingItem();
            require(!player.isUsingItem() && box.getCount() == 1
                    && !BlindBoxItem.hasActiveUseState(player, box)
                    && BlindBoxItem.openingStartTick(box) == -1L
                    && !box.getTag().contains("blindboxchallenge_opening_duration"), "取消后消耗或快照残留");
            require(pool.transactions().isEmpty() && pool.bundles().isEmpty(), "空池或取消流程产生交易");
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_OPENING_OK 时长快照=80 下一次=10 取消无消耗=true 空池无消耗=true"), true);
            return 1;
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("BLINDBOX_CITEST_OPENING_FAILED " + error.getMessage()));
            return 0;
        } finally {
            player.stopUsingItem();
            BlindBoxItem.cancelUse(player);
            ModServerConfig.BLIND_BOX_OPENING_TICKS.set(original);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class OpeningPlayer extends FakePlayer {
        OpeningPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.fromString("53bdcc00-dc4a-4ae9-9a0a-12d6933e775b"), "OpeningProbe"));
        }

        void advanceOpening() {
            updateUsingItem(getUseItem());
        }
    }

    private BlindBoxOpeningCiAssertions() {}
}
