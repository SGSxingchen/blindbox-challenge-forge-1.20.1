package cn.blindboxchallenge.client;

import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.network.CommitPackingPacket;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.service.BlindBoxService;
import cn.blindboxchallenge.util.StackFingerprint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** P1 故意采用可审计的“槽位:数量”输入，避免客户端把 ItemStack 直接发送给服务器。 */
public final class PackingScreen extends AbstractContainerScreen<PackingMenu> {
    private Component error = Component.empty();

    public PackingScreen(PackingMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 8;
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.pack"), button -> submit())
                .bounds(leftPos + 8, topPos + 18, 92, 20).build());
    }

    private void submit() {
        try {
            List<BlindBoxService.Selection> values = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                var stack = minecraft.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) values.add(new BlindBoxService.Selection(slot, stack.getCount(), StackFingerprint.of(stack)));
            }
            if (values.isEmpty()) throw new IllegalArgumentException();
            ModNetwork.CHANNEL.sendToServer(new CommitPackingPacket(menu.containerId, menu.sessionId(), values));
            error = Component.empty();
        } catch (RuntimeException ignored) {
            error = Component.translatable("screen.blindboxchallenge.invalid_selection");
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC2B2137);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 78, 0xCC4B385D);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!error.equals(Component.empty())) graphics.drawString(font, error, leftPos + 8, topPos + 76, 0xFF7777, false);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
