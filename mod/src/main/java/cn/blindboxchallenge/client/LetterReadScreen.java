package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.LetterReadEvent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** 信件只读界面。服务端已过滤正文，客户端不解析 JSON、事件或格式码。 */
public final class LetterReadScreen extends Screen {
    private final String body;
    private List<FormattedCharSequence> wrappedLines = List.of();
    private int page;
    private int linesPerPage;
    private int paperHeight;
    private Button previousPage;
    private Button nextPage;

    public LetterReadScreen(String body) {
        super(Component.translatable("screen.blindboxchallenge.letter_read"));
        this.body = body;
    }

    @Override
    protected void init() {
        wrappedLines = font.split(Component.literal(body), 200);
        paperHeight = Math.min(210, Math.max(90, height - 70));
        linesPerPage = Math.max(1, (paperHeight - 66) / 10);
        page = Math.min(page, lastPage());
        int navigationY = paperTop() + paperHeight - 28;
        previousPage = addRenderableWidget(Button.builder(Component.literal("←"), button -> changePage(-1))
                .bounds(width / 2 - 96, navigationY, 36, 20).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal("→"), button -> changePage(1))
                .bounds(width / 2 + 60, navigationY, 36, 20).build());
        updatePageButtons();
        addRenderableWidget(Button.builder(Component.translatable("screen.blindboxchallenge.close"), button -> onClose())
                .bounds(width / 2 - 40, height - 38, 80, 20).build());
    }

    private int paperTop() { return Math.max(18, height / 2 - paperHeight / 2 - 15); }

    private int lastPage() { return Math.max(0, (wrappedLines.size() - 1) / linesPerPage); }

    private void changePage(int direction) {
        page = Math.max(0, Math.min(lastPage(), page + direction));
        updatePageButtons();
    }

    private void updatePageButtons() {
        previousPage.active = page > 0;
        nextPage.active = page < lastPage();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta == 0) return false;
        changePage(delta > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_LEFT) {
            changePage(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_RIGHT) {
            changePage(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 112;
        int top = paperTop();
        graphics.fill(left, top, left + 224, top + paperHeight, 0xFFE9D9AE);
        graphics.fill(left + 3, top + 3, left + 221, top + paperHeight - 3, 0xFFF8ECCD);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0x4A3422);
        int y = top + 30;
        int end = Math.min(wrappedLines.size(), (page + 1) * linesPerPage);
        for (int index = page * linesPerPage; index < end; index++) {
            graphics.drawString(font, wrappedLines.get(index), left + 12, y, 0x3B2A1F, false);
            y += 10;
        }
        graphics.drawCenteredString(font, (page + 1) + " / " + (lastPage() + 1), width / 2, top + paperHeight - 22, 0x4A3422);
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
