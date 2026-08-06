package cn.blindboxchallenge.menu;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.registry.ModMenus;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 八音盒 URL 编辑的服务端受控会话，不提供物品槽。 */
public final class MusicBoxMenu extends AbstractContainerMenu {
    private final UUID sessionId;
    private final BlockPos position;
    private final UUID instanceId;
    private final int revision;
    private final String url;
    private boolean submissionConsumed;

    public MusicBoxMenu(int containerId, Inventory inventory, UUID sessionId, BlockPos position, UUID instanceId, int revision, String url) {
        super(ModMenus.MUSIC_BOX_MENU.get(), containerId);
        this.sessionId = sessionId;
        this.position = position.immutable();
        this.instanceId = instanceId;
        this.revision = revision;
        this.url = url;
    }

    public MusicBoxMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readUUID(), buffer.readBlockPos(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readUtf(2048));
    }

    public UUID sessionId() { return sessionId; }
    public BlockPos position() { return position; }
    public UUID instanceId() { return instanceId; }
    public int revision() { return revision; }
    public String url() { return url; }
    public boolean submissionConsumed() { return submissionConsumed; }

    public boolean consumeSubmission() {
        if (submissionConsumed) return false;
        submissionConsumed = true;
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return !player.isSpectator() && player.level().getBlockEntity(position) instanceof MusicBoxBlockEntity
                && player.distanceToSqr(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
