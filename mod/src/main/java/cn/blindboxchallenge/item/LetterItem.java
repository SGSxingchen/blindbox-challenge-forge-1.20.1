package cn.blindboxchallenge.item;

import cn.blindboxchallenge.menu.LetterEditMenu;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.network.ShowLetterPacket;
import cn.blindboxchallenge.service.LetterService;
import cn.blindboxchallenge.config.ModServerConfig;
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
import net.minecraftforge.network.PacketDistributor;

/** 信件的正文只由服务端保存；客户端只得到受限纯文本的显示副本。 */
public final class LetterItem extends Item {
    public LetterItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            int slot = LetterService.handSlot(serverPlayer, hand);
            UUID instanceId = LetterService.ensureInstanceId(stack);
            int revision = LetterService.revision(stack);
            String safeBody;
            try {
                safeBody = LetterService.safeBodyForRead(stack);
            } catch (IllegalArgumentException ignored) {
                serverPlayer.displayClientMessage(Component.translatable("message.blindboxchallenge.letter_invalid_data"), true);
                return InteractionResultHolder.fail(stack);
            }
            if (player.isShiftKeyDown()) {
                UUID sessionId = UUID.randomUUID();
                MenuProvider provider = new SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new LetterEditMenu(containerId, inventory, sessionId, slot, instanceId, revision,
                                safeBody, ModServerConfig.LETTER_MAX_CODE_POINTS.get(), ModServerConfig.LETTER_MAX_LINES.get()),
                        Component.translatable("menu.blindboxchallenge.letter_edit"));
                NetworkHooks.openScreen(serverPlayer, provider, buffer -> {
                    buffer.writeUUID(sessionId);
                    buffer.writeVarInt(slot);
                    buffer.writeUUID(instanceId);
                    buffer.writeVarInt(revision);
                    buffer.writeUtf(safeBody, LetterService.MAX_NETWORK_BODY_LENGTH);
                    buffer.writeVarInt(ModServerConfig.LETTER_MAX_CODE_POINTS.get());
                    buffer.writeVarInt(ModServerConfig.LETTER_MAX_LINES.get());
                });
            } else {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new ShowLetterPacket(instanceId, revision, safeBody));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
