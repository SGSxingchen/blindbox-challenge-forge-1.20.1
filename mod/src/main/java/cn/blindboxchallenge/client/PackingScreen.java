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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/** 只记录玩家明确选择的槽位和数量；背包物品留在原位，由服务端重新校验。 */
public final class PackingScreen extends AbstractContainerScreen<PackingMenu> {
    private final BlindBoxService.Selection[] selections = new BlindBoxService.Selection[36];
    private Button packButton;
    private boolean submitted;
    private Component error = Component.empty();

    public PackingScreen(PackingMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 8;
        packButton = addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.pack"), button -> submit())
                .bounds(leftPos + 8, topPos + 18, imageWidth - 16, 20).build());
        updateButton();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        // 数字换槽、丢弃和拖动均不执行原版库存操作，避免选择过程改变真实物品。
        if (submitted || slot == null || (clickType != ClickType.PICKUP && clickType != ClickType.PICKUP_ALL)
                || (mouseButton != 0 && mouseButton != 1)) return;
        int inventorySlot = slot.getSlotIndex();
        if (inventorySlot < 0 || inventorySlot >= selections.length) return;
        var stack = slot.getItem();
        if (stack.isEmpty() || BlindBoxService.isForbidden(stack)) {
            selections[inventorySlot] = null;
        } else {
            String fingerprint = StackFingerprint.of(stack);
            BlindBoxService.Selection selected = selections[inventorySlot];
            if (selected != null && !selected.fingerprint().equals(fingerprint)) selected = null;
            int count = mouseButton == 0 ? (selected == null ? stack.getCount() : 0)
                    : (selected == null ? 0 : selected.count() - 1);
            selections[inventorySlot] = count > 0 ? new BlindBoxService.Selection(inventorySlot, count, fingerprint) : null;
        }
        error = Component.empty();
        updateButton();
    }

    private void updateButton() {
        boolean hasSelection = false;
        for (BlindBoxService.Selection selected : selections) hasSelection |= selected != null;
        packButton.active = hasSelection && !submitted;
    }

    private void submit() {
        if (submitted) return;
        try {
            List<BlindBoxService.Selection> values = new ArrayList<>();
            for (BlindBoxService.Selection selected : selections) {
                if (selected == null) continue;
                var stack = minecraft.player.getInventory().getItem(selected.slot());
                if (stack.isEmpty() || BlindBoxService.isForbidden(stack) || selected.count() > stack.getCount()
                        || !selected.fingerprint().equals(StackFingerprint.of(stack))) throw new IllegalArgumentException();
                values.add(selected);
            }
            if (values.isEmpty()) throw new IllegalArgumentException();
            ModNetwork.CHANNEL.sendToServer(new CommitPackingPacket(menu.containerId, menu.sessionId(), values));
            submitted = true;
            updateButton();
            error = Component.empty();
        } catch (RuntimeException ignored) {
            error = Component.translatable("screen.blindboxchallenge.invalid_selection");
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC2B2137);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 78, 0xCC4B385D);
        for (Slot slot : menu.slots) {
            if (selections[slot.getSlotIndex()] != null) {
                graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF6AAB69);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        Component hint = error.equals(Component.empty()) ? Component.translatable("screen.blindboxchallenge.packing_hint") : error;
        graphics.drawWordWrap(font, hint, leftPos + 8, topPos + 44, imageWidth - 16, error.equals(Component.empty()) ? 0xFFFFFF : 0xFF7777);
        for (Slot slot : menu.slots) {
            BlindBoxService.Selection selected = selections[slot.getSlotIndex()];
            if (selected != null) graphics.drawString(font, "×" + selected.count(), leftPos + slot.x, topPos + slot.y, 0xAAFFAA, true);
        }
        renderTooltip(graphics, mouseX, mouseY);
    }
}
