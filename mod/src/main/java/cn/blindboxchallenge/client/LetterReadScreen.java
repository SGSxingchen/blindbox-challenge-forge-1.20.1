package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.LetterReadEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 信件只读界面。服务端已过滤正文，客户端不解析 JSON、事件或格式码。 */
public final class LetterReadScreen extends Screen {
    private final String body;

    public LetterReadScreen(String body) {
        super(Component.translatable("screen.blindboxchallenge.letter_read"));
        this.body = body;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.close"), button -> onClose())
                .bounds(width / 2 - 40, height - 38, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 112;
        int top = Math.max(18, height / 2 - 105);
        graphics.fill(left, top, left + 224, top + 210, 0xFFE9D9AE);
        graphics.fill(left + 3, top + 3, left + 221, top + 207, 0xFFF8ECCD);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0x4A3422);
        int y = top + 30;
        for (FormattedCharSequence line : font.split(Component.literal(body), 200)) {
            if (y > top + 184) break;
            graphics.drawString(font, line, left + 12, y, 0x3B2A1F, false);
            y += 10;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, value = Dist.CLIENT)
    public static final class Listener {
        @SubscribeEvent
        public static void show(LetterReadEvent event) {
            Minecraft.getInstance().setScreen(new LetterReadScreen(event.body()));
        }

        private Listener() {}
    }
}
