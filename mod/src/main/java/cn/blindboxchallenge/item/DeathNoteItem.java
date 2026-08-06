package cn.blindboxchallenge.item;

import cn.blindboxchallenge.menu.DeathNoteMenu;
import cn.blindboxchallenge.service.LetterService;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/** 死亡笔记只建立服务端受控会话，绝不把玩家输入解释为命令。 */
public final class DeathNoteItem extends Item {
    public DeathNoteItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            int slot = LetterService.handSlot(serverPlayer, hand);
            UUID sessionId = UUID.randomUUID();
            UUID instanceId = LetterService.ensureInstanceId(stack);
            int revision = LetterService.revision(stack);
            MenuProvider provider = new SimpleMenuProvider(
                    (containerId, inventory, ignored) -> new DeathNoteMenu(containerId, inventory, sessionId, slot, instanceId, revision),
                    Component.translatable("menu.blindboxchallenge.death_note"));
            NetworkHooks.openScreen(serverPlayer, provider, buffer -> {
                buffer.writeUUID(sessionId);
                buffer.writeVarInt(slot);
                buffer.writeUUID(instanceId);
                buffer.writeVarInt(revision);
            });
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
