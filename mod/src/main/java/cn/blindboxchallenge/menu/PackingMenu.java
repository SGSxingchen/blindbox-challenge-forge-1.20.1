package cn.blindboxchallenge.menu;

import cn.blindboxchallenge.registry.ModMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.FriendlyByteBuf;

/** 仅展示玩家背包；选择以 C2S 的槽位+数量提交，服务器会再次读取真实槽位。 */
public final class PackingMenu extends AbstractContainerMenu {
    public PackingMenu(int containerId, Inventory inventory) {
        super(ModMenus.PACKING_MENU.get(), containerId);
        // 主背包索引 9..35；槽位索引与玩家 Inventory 保持一致，便于服务端指纹验证。
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 142));
    }

    public PackingMenu(int containerId, Inventory inventory, FriendlyByteBuf ignored) {
        this(containerId, inventory);
    }

    @Override
    public boolean stillValid(Player player) { return !player.isSpectator(); }
}
