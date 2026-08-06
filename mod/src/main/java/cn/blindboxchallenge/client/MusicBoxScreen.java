package cn.blindboxchallenge.client;

import cn.blindboxchallenge.menu.MusicBoxMenu;
import cn.blindboxchallenge.network.CommitMusicBoxUrlPacket;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 仅客户端输入框；最终 HTTPS 规则、方块实例和修订仍由服务端 C2S 重新校验。 */
public final class MusicBoxScreen extends AbstractContainerScreen<MusicBoxMenu> {
    private EditBox url;
    private Component error = Component.empty();

    public MusicBoxScreen(MusicBoxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 286;
        imageHeight = 116;
    }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 12;
        url = addRenderableWidget(new EditBox(font, leftPos + 12, topPos + 38, 262, 20,
                Component.translatable("screen.blindboxchallenge.music_box_url")));
        url.setMaxLength(AudioUrlPolicy.MAX_URL_LENGTH);
        url.setValue(menu.url());
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.save"), button -> submit())
                .bounds(leftPos + 98, topPos + 76, 88, 20).build());
    }

    private void submit() {
        String value = url.getValue();
        if (value.length() > AudioUrlPolicy.MAX_URL_LENGTH || !value.startsWith("https://")) {
            error = Component.translatable("screen.blindboxchallenge.music_box_invalid_url");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new CommitMusicBoxUrlPacket(menu.containerId, menu.sessionId(), menu.position(),
                menu.instanceId(), menu.revision(), value));
        error = Component.empty();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF5B3A21);
        graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xFFD7AA67);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable("screen.blindboxchallenge.music_box_hint"), leftPos + 12, topPos + 24, 0x3A2618, false);
        if (!error.equals(Component.empty())) graphics.drawString(font, error, leftPos + 12, topPos + 100, 0xB22222, false);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
