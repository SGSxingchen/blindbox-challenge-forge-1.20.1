package cn.blindboxchallenge.item;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.service.PigBreedingService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 011：书本不消耗；猪扫描、繁殖与冷却仅由服务端决定。 */
public final class EfficientPigBreedingItem extends Item {
    public EfficientPigBreedingItem() { super(new Item.Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true);
        if (!(player instanceof ServerPlayer serverPlayer) || player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        PigBreedingService.BreedingResult result = PigBreedingService.breedNearby(serverPlayer);
        if (result.bredPairCount() == 0) return InteractionResultHolder.fail(stack);
        player.getCooldowns().addCooldown(this, ModServerConfig.EFFICIENT_PIG_COOLDOWN_TICKS.get());
        level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.7F, 0.85F);
        return InteractionResultHolder.consume(stack);
    }
}
