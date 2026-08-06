package cn.blindboxchallenge.menu;

import cn.blindboxchallenge.registry.ModMenus;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 死亡笔记菜单的会话只允许一个成功提交进入服务端排程。 */
public final class DeathNoteMenu extends AbstractContainerMenu {
    private final UUID sessionId;
    private final int heldSlot;
    private final UUID instanceId;
    private final int revision;
    private boolean submissionConsumed;

    public DeathNoteMenu(int containerId, Inventory inventory, UUID sessionId, int heldSlot, UUID instanceId, int revision) {
        super(ModMenus.DEATH_NOTE_MENU.get(), containerId);
        this.sessionId = sessionId;
        this.heldSlot = heldSlot;
        this.instanceId = instanceId;
        this.revision = revision;
    }

    public DeathNoteMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt());
    }

    public UUID sessionId() { return sessionId; }
    public int heldSlot() { return heldSlot; }
    public UUID instanceId() { return instanceId; }
    public int revision() { return revision; }

    public boolean consumeSubmission() {
        if (submissionConsumed) return false;
        submissionConsumed = true;
        return true;
    }

    public boolean submissionConsumed() { return submissionConsumed; }

    @Override
    public boolean stillValid(Player player) {
        return !player.isSpectator();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
