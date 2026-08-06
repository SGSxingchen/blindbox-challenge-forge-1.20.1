package cn.blindboxchallenge.client;

import cn.blindboxchallenge.menu.LetterEditMenu;
import cn.blindboxchallenge.network.CommitLetterEditPacket;
import cn.blindboxchallenge.network.ModNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 十六个普通输入行组成的信纸编辑器，最终文本仍由服务端按码点和行数裁决。 */
public final class LetterEditScreen extends AbstractContainerScreen<LetterEditMenu> {
    private final List<EditBox> lines = new ArrayList<>();
    private Component error = Component.empty();

    public LetterEditScreen(LetterEditMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 244;
        imageHeight = 270;
    }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 10;
        String[] original = menu.originalBody().split("\\n", -1);
        for (int index = 0; index < menu.maximumLines(); index++) {
            EditBox line = new EditBox(font, leftPos + 12, topPos + 24 + index * 12, 220, 11,
                    Component.translatable("screen.blindboxchallenge.letter_line", index + 1));
            // 补充字符占两个 UTF-16 单元；不能用默认 512 静默截断服务器允许的 4096 码点旧信件。
            line.setMaxLength(menu.maximumCodePoints() * 2);
            if (index < original.length) line.setValue(original[index]);
            lines.add(addRenderableWidget(line));
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.save"), button -> submit())
                .bounds(leftPos + 78, topPos + 224, 88, 20).build());
    }

    private void submit() {
        int lastNonEmpty = lines.size() - 1;
        while (lastNonEmpty >= 0 && lines.get(lastNonEmpty).getValue().isEmpty()) lastNonEmpty--;
        StringBuilder body = new StringBuilder();
        for (int index = 0; index <= lastNonEmpty; index++) {
            if (index > 0) body.append('\n');
            body.append(lines.get(index).getValue());
        }
        if (body.codePointCount(0, body.length()) > menu.maximumCodePoints()) {
            error = Component.translatable("screen.blindboxchallenge.letter_too_long");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new CommitLetterEditPacket(menu.containerId, menu.sessionId(), menu.heldSlot(),
                menu.instanceId(), menu.revision(), body.toString()));
        error = Component.empty();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFE9D9AE);
        graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xFFF8ECCD);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable("screen.blindboxchallenge.letter_hint", menu.maximumCodePoints(), menu.maximumLines()),
                leftPos + 12, topPos + 210, 0x5C4730, false);
        if (!error.equals(Component.empty())) graphics.drawString(font, error, leftPos + 12, topPos + 248, 0xB22222, false);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
