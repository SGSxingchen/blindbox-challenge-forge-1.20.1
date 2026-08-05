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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** P1 故意采用可审计的“槽位:数量”输入，避免客户端把 ItemStack 直接发送给服务器。 */
public final class PackingScreen extends AbstractContainerScreen<PackingMenu> {
    private EditBox selection;
    private Component error = Component.empty();

    public PackingScreen(PackingMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 8;
        selection = new EditBox(font, leftPos + 8, topPos + 18, 150, 18, Component.translatable("screen.blindboxchallenge.selection"));
        selection.setMaxLength(160);
        selection.setValue("");
        addRenderableWidget(selection);
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.pack"), button -> submit())
                .bounds(leftPos + 8, topPos + 40, 92, 20).build());
    }

    private void submit() {
        try {
            List<BlindBoxService.Selection> values = new ArrayList<>();
            String raw = selection.getValue().trim();
            if (raw.isEmpty()) throw new IllegalArgumentException();
            for (String pair : raw.split(",")) {
                String[] parts = pair.trim().split(":");
                if (parts.length != 2) throw new IllegalArgumentException();
                int slot = Integer.parseInt(parts[0].trim());
                int count = Integer.parseInt(parts[1].trim());
                if (slot < 0 || slot >= 36 || count <= 0 || values.stream().anyMatch(value -> value.slot() == slot)) throw new IllegalArgumentException();
                var stack = minecraft.player.getInventory().getItem(slot);
                values.add(new BlindBoxService.Selection(slot, count, StackFingerprint.of(stack)));
            }
            ModNetwork.CHANNEL.sendToServer(new CommitPackingPacket(menu.containerId, values));
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
        graphics.drawString(font, Component.translatable("screen.blindboxchallenge.help"), leftPos + 8, topPos + 64, 0xEAD9FF, false);
        if (!error.equals(Component.empty())) graphics.drawString(font, error, leftPos + 8, topPos + 76, 0xFF7777, false);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
