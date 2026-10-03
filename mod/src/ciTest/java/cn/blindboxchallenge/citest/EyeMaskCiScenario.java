package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.registry.ModItems;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 只在隔离 ciTest 中跨真实服务端 tick 验证装备、牛奶、死亡保护与外部失明自然到期。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID)
public final class EyeMaskCiScenario {
    private static Active active;
    private static boolean completed;

    private EyeMaskCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("眼罩验证正在运行"));
            return 0;
        }
        completed = false;
        try {
            ServerPlayer player = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (player == null) throw new IllegalStateException("眼罩验证需要 Alice 在线");
            active = new Active(player);
            active.prepare();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_EYE_MASK_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            if (active != null) active.restore();
            active = null;
            CiTestProbe.LOGGER.error("眼罩验证启动失败", exception);
            source.sendFailure(Component.literal("眼罩验证启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verify(CommandSourceStack source) {
        if (!completed || active != null) {
            source.sendFailure(Component.literal("眼罩真实跨刻验证未完成或已失败"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_EYE_MASK=success"), false);
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            if (active.tick()) {
                active.restore();
                active = null;
                completed = true;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_EYE_MASK_FINISHED=success");
            }
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_EYE_MASK=failed", exception);
            try {
                active.restore();
            } finally {
                active = null;
                completed = false;
            }
        }
    }

    private static final class Active {
        private final ServerPlayer player;
        private final ItemStack originalHead;
        private final ItemStack originalOffhand;
        private final List<MobEffectInstance> originalEffects;
        private final boolean originalOwnedBlindness;
        private final float originalHealth;
        private final int originalInvulnerableTime;
        private int phase;
        private int phaseTicks;

        private Active(ServerPlayer player) {
            this.player = player;
            originalHead = player.getItemBySlot(EquipmentSlot.HEAD).copy();
            originalOffhand = player.getOffhandItem().copy();
            originalEffects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            originalOwnedBlindness = EggyEyeMaskItem.ownsCurrentBlindness(player);
            originalHealth = player.getHealth();
            originalInvulnerableTime = player.invulnerableTime;
        }

        private void prepare() {
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            player.removeAllEffects();
        }

        private boolean tick() {
            if (player.isRemoved() || !player.isAlive()) throw new IllegalStateException("眼罩验证玩家已离线或死亡");
            if (++phaseTicks > 40) throw new IllegalStateException("眼罩验证超时，阶段=" + phase);
            if (phase == 0) {
                // 装备事件由实体刻派发；先让真实卸下完成，再戴上，不能同刻换回导致事件看不见变化。
                if (phaseTicks < 2) return false;
                if (player.hasEffect(MobEffects.BLINDNESS)) throw new IllegalStateException("初始卸下眼罩后仍有失明");
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.EGGY_EYE_MASK.get()));
                advance();
            } else if (phase == 1) {
                // 命令可能发生在实体刻之后；只在真实装备事件已经安装自身失明后测试牛奶。
                if (!EggyEyeMaskItem.ownsCurrentBlindness(player)) return false;
                Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET), player.serverLevel(), player);
                advance();
            } else if (phase == 2) {
                // 效果移除事件先于原版修改效果表，回补要等正式刻末维护，而不是同刻直接调用维护方法。
                if (!EggyEyeMaskItem.ownsCurrentBlindness(player)) return false;
                ItemStack totem = new ItemStack(ModItems.RAT_JERKY_TOTEM.get());
                player.setItemInHand(InteractionHand.OFF_HAND, totem);
                player.invulnerableTime = 0;
                player.setHealth(0.5F);
                player.hurt(player.damageSources().generic(), 100.0F);
                if (!player.isAlive() || !totem.isEmpty() || player.getHealth() != 1.0F) {
                    throw new IllegalStateException("眼罩验证的真实伤害没有触发自定义图腾死亡保护");
                }
                advance();
            } else if (phase == 3) {
                if (!EggyEyeMaskItem.ownsCurrentBlindness(player)) return false;
                // 外部较强失明覆盖时，取下眼罩不能删除外部效果或留下隐藏的无限失明。
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 4, 1));
                player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                MobEffectInstance external = player.getEffect(MobEffects.BLINDNESS);
                if (external == null || external.getAmplifier() != 1 || external.getDuration() != 4
                        || external.save(new net.minecraft.nbt.CompoundTag()).contains("HiddenEffect")) {
                    throw new IllegalStateException("卸眼罩误删外部失明或在外部失明下留下无限效果");
                }
                advance();
            } else if (phase == 4 && phaseTicks >= 6) {
                if (player.hasEffect(MobEffects.BLINDNESS)) throw new IllegalStateException("卸眼罩后外部失明到期仍有失明");
                // 先有外部效果，再戴眼罩：自然到期必须由正式效果事件补回眼罩失明。
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 4, 0));
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.EGGY_EYE_MASK.get()));
                advance();
            } else if (phase == 5 && phaseTicks >= 6) {
                if (!EggyEyeMaskItem.ownsCurrentBlindness(player)) return false;
                // 外部无限失明也必须独立保留，不能只靠无限时长判断来源。
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, -1, 0, false, true, true));
                player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                advance();
            } else if (phase == 6 && phaseTicks >= 2) {
                MobEffectInstance external = player.getEffect(MobEffects.BLINDNESS);
                if (external == null || !external.isInfiniteDuration() || !external.isVisible() || !external.showIcon()) {
                    throw new IllegalStateException("卸眼罩误删外部无限失明");
                }
                if (EggyEyeMaskItem.ownsCurrentBlindness(player)) throw new IllegalStateException("外部无限失明仍被误认为眼罩自带效果");
                return true;
            }
            return false;
        }

        private void advance() {
            CiTestProbe.LOGGER.info("眼罩真实事件观察完成，阶段={}", phase);
            phase++;
            phaseTicks = 0;
        }

        private void restore() {
            // 以下调用只负责恢复测试前状态，不计入任何阶段的成功证据。
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            EggyEyeMaskItem.onUnequipped(player);
            player.removeAllEffects();
            player.setItemSlot(EquipmentSlot.HEAD, originalHead);
            for (MobEffectInstance effect : originalEffects) player.addEffect(new MobEffectInstance(effect));
            if (originalOwnedBlindness) {
                player.removeEffect(MobEffects.BLINDNESS);
                EggyEyeMaskItem.onEquipped(player);
            }
            player.setItemInHand(InteractionHand.OFF_HAND, originalOffhand);
            player.setHealth(Math.min(originalHealth, player.getMaxHealth()));
            player.invulnerableTime = originalInvulnerableTime;
            player.containerMenu.broadcastChanges();
        }
    }
}
