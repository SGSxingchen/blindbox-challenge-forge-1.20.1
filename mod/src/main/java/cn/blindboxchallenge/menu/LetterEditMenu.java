package cn.blindboxchallenge.menu;

import cn.blindboxchallenge.registry.ModMenus;
import cn.blindboxchallenge.service.LetterService;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 信件编辑菜单不暴露临时物品槽，只为客户端输入建立一次性服务端会话。 */
public final class LetterEditMenu extends AbstractContainerMenu {
    private final UUID sessionId;
    private final int heldSlot;
    private final UUID instanceId;
    private final int revision;
    private final String originalBody;
    private final int maximumCodePoints;
    private final int maximumLines;
    private boolean submissionConsumed;

    public LetterEditMenu(int containerId, Inventory inventory, UUID sessionId, int heldSlot, UUID instanceId, int revision) {
        this(containerId, inventory, sessionId, heldSlot, instanceId, revision, "", 512, 16);
    }

    public LetterEditMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readUtf(LetterService.MAX_NETWORK_BODY_LENGTH), buffer.readVarInt(), buffer.readVarInt());
    }

    public LetterEditMenu(int containerId, Inventory inventory, UUID sessionId, int heldSlot, UUID instanceId, int revision,
                          String originalBody, int maximumCodePoints, int maximumLines) {
        super(ModMenus.LETTER_EDIT_MENU.get(), containerId);
        this.sessionId = sessionId;
        this.heldSlot = heldSlot;
        this.instanceId = instanceId;
        this.revision = revision;
        this.originalBody = originalBody;
        this.maximumCodePoints = maximumCodePoints;
        this.maximumLines = maximumLines;
    }

    public UUID sessionId() { return sessionId; }
    public int heldSlot() { return heldSlot; }
    public UUID instanceId() { return instanceId; }
    public int revision() { return revision; }
    public String originalBody() { return originalBody; }
    public int maximumCodePoints() { return maximumCodePoints; }
    public int maximumLines() { return maximumLines; }

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
