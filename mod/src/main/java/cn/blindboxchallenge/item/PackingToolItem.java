package cn.blindboxchallenge.item;

import cn.blindboxchallenge.menu.PackingMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/** 打包不会把物品移入临时容器；菜单只提交服务端库存槽位与数量。 */
public final class PackingToolItem extends Item {
    public PackingToolItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MenuProvider provider = new MenuProvider() {
                @Override public Component getDisplayName() { return Component.translatable("menu.blindboxchallenge.packing"); }
                @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory, Player ignored) {
                    return new PackingMenu(id, inventory);
                }
            };
            NetworkHooks.openScreen(serverPlayer, provider);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
