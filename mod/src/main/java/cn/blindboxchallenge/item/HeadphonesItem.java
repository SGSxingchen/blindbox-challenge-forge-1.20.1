package cn.blindboxchallenge.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 027：只由服务端广播原版唱片声源；不接受、保存、下载或解码任何 URL/在线音频。
 * 原版声音事件由客户端按原版资源包播放，播完自然结束，物品本身不维持播放状态。
 */
public final class HeadphonesItem extends Item {
    public static final int COOLDOWN_TICKS = 20;

    public HeadphonesItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) return InteractionResultHolder.fail(stack);
        if (!level.isClientSide) {
            level.playSound(null, player.blockPosition(), SoundEvents.MUSIC_DISC_CAT, SoundSource.PLAYERS, 0.65F, 1.0F);
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
