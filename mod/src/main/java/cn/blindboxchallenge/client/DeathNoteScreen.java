package cn.blindboxchallenge.client;

import cn.blindboxchallenge.menu.DeathNoteMenu;
import cn.blindboxchallenge.network.CommitDeathNotePacket;
import cn.blindboxchallenge.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 死亡笔记只让客户端输入玩家名；目标解析、延迟和伤害均不在这里发生。 */
public final class DeathNoteScreen extends AbstractContainerScreen<DeathNoteMenu> {
    private EditBox targetName;
    private Component error = Component.empty();

    public DeathNoteScreen(DeathNoteMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 216;
        imageHeight = 108;
    }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 10;
        targetName = new EditBox(font, leftPos + 12, topPos + 30, 192, 18, Component.translatable("screen.blindboxchallenge.death_note_target"));
        targetName.setMaxLength(16);
        addRenderableWidget(targetName);
        targetName.setFocused(true);
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.confirm"), button -> submit())
                .bounds(leftPos + 64, topPos + 56, 88, 20).build());
    }

    private void submit() {
        String name = targetName.getValue();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            error = Component.translatable("screen.blindboxchallenge.death_note_invalid_target");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new CommitDeathNotePacket(menu.containerId, menu.sessionId(), menu.heldSlot(),
                menu.instanceId(), menu.revision(), name));
        error = Component.empty();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF201820);
        graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xFF3D2635);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!error.equals(Component.empty())) graphics.drawString(font, error, leftPos + 12, topPos + 94, 0xFF7777, false);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
