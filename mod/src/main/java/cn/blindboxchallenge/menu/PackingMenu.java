package cn.blindboxchallenge.menu;

import cn.blindboxchallenge.registry.ModMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** 仅展示玩家背包；选择以 C2S 的槽位+数量提交，服务器会再次读取真实槽位。 */
public final class PackingMenu extends AbstractContainerMenu {
    private final UUID sessionId;
    private boolean submissionConsumed;
    public PackingMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, UUID.randomUUID());
    }
    public PackingMenu(int containerId, Inventory inventory, UUID sessionId) {
        super(ModMenus.PACKING_MENU.get(), containerId);
        this.sessionId = sessionId;
        // 主背包索引 9..35；槽位索引与玩家 Inventory 保持一致，便于服务端指纹验证。
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 142));
    }

    public PackingMenu(int containerId, Inventory inventory, FriendlyByteBuf ignored) {
        this(containerId, inventory, ignored.readUUID());
    }

    public UUID sessionId() { return sessionId; }

    /** 同一菜单会话只允许一个合法提交进入业务层，阻断重复包和有限突发重放。 */
    public boolean consumeSubmission() {
        if (submissionConsumed) return false;
        submissionConsumed = true;
        return true;
    }

    public boolean submissionConsumed() { return submissionConsumed; }

    @Override
    public boolean stillValid(Player player) { return !player.isSpectator(); }

    /** P1 的数量选择由受校验的文本提交完成，禁止 Shift+点击制造另一个临时库存副本。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
