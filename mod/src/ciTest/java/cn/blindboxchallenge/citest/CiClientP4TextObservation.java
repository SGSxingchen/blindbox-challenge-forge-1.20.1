package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.client.DeathNoteScreen;
import cn.blindboxchallenge.client.LetterEditScreen;
import cn.blindboxchallenge.client.LetterReadScreen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import cn.blindboxchallenge.registry.ModItems;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 仅 ciTest Jar 中的真实界面驱动器：它不直接发业务包，而是只在实际收到生产 Screen 后以鼠标和
 * 字符事件点击生产控件。marker 只能在服务端关闭两次菜单后写出，服务端还会核对 NBT、排程和死亡。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientP4TextObservation {
    private enum Phase { RIGHT_CLICK_LETTER, WAIT_READ, ARM_SNEAK, WAIT_SNEAK_SYNC, WAIT_LETTER, RIGHT_CLICK_NOTE, WAIT_NOTE, WAIT_CLOSE, DONE }

    private static Phase phase = Phase.RIGHT_CLICK_LETTER;
    private static boolean readOnlyScreenObserved;
    private static boolean letterEditClicked;
    private static boolean deathNoteClicked;
    private static boolean normalUseKeyInjected;
    private static boolean sneakUseKeyInjected;
    private static int sneakSyncTicks;
    private static boolean bobDeathWritten;

    private CiClientP4TextObservation() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) return;
        // P4 文本负例也会临时持有生产信件；未收到脚本在服务端 STARTED 后写入的阶段旗标前，
        // 绝不能驱动真实 GUI 或写 marker，否则会污染随后严格拒旧 marker 的正向场景。
        if (!scenarioEnabled()) return;
        observeBobDeath(minecraft, player);
        if (phase == Phase.DONE || markerPath() == null) return;
        Screen screen = minecraft.screen;
        switch (phase) {
            case RIGHT_CLICK_LETTER -> {
                if (screen == null && player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.LETTER.get())) {
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    normalUseKeyInjected = true;
                    phase = Phase.WAIT_READ;
                }
            }
            case WAIT_READ -> {
                if (screen instanceof LetterReadScreen) {
                    readOnlyScreenObserved = true;
                    // 点击生产“关闭”按钮，而不是直接切换 Screen，确保信纸界面真实可交互。
                    screen.mouseClicked(screen.width / 2.0D, screen.height - 28.0D, 0);
                    screen.mouseReleased(screen.width / 2.0D, screen.height - 28.0D, 0);
                    phase = Phase.ARM_SNEAK;
                }
            }
            case ARM_SNEAK -> {
                if (screen == null && player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.LETTER.get())) {
                    KeyMapping.set(minecraft.options.keyShift.getKey(), true);
                    sneakSyncTicks = 0;
                    phase = Phase.WAIT_SNEAK_SYNC;
                }
            }
            case WAIT_SNEAK_SYNC -> {
                if (++sneakSyncTicks >= 4) {
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    // use 点击会在后续游戏 tick 消费；必须保持潜行直到实际收到编辑菜单，
                    // 不能同 tick 释放而把潜行右键降级为普通只读右键。
                    sneakUseKeyInjected = true;
                    phase = Phase.WAIT_LETTER;
                }
            }
            case WAIT_LETTER -> {
                if (screen instanceof LetterEditScreen) {
                    KeyMapping.set(minecraft.options.keyShift.getKey(), false);
                    int left = (screen.width - 244) / 2;
                    int top = (screen.height - 270) / 2;
                    screen.mouseClicked(left + 20.0D, top + 29.0D, 0);
                    for (char character : P4TextCiScenario.LETTER_BODY.toCharArray()) screen.charTyped(character, 0);
                    screen.mouseClicked(left + 122.0D, top + 234.0D, 0);
                    screen.mouseReleased(left + 122.0D, top + 234.0D, 0);
                    letterEditClicked = true;
                    phase = Phase.RIGHT_CLICK_NOTE;
                }
            }
            case RIGHT_CLICK_NOTE -> {
                if (screen == null && player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.DEATH_NOTE.get())) {
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    phase = Phase.WAIT_NOTE;
                }
            }
            case WAIT_NOTE -> {
                if (screen instanceof DeathNoteScreen) {
                    int left = (screen.width - 216) / 2;
                    int top = (screen.height - 108) / 2;
                    screen.mouseClicked(left + 20.0D, top + 38.0D, 0);
                    for (char character : "BlindBoxBob".toCharArray()) screen.charTyped(character, 0);
                    screen.mouseClicked(left + 108.0D, top + 66.0D, 0);
                    screen.mouseReleased(left + 108.0D, top + 66.0D, 0);
                    deathNoteClicked = true;
                    phase = Phase.WAIT_CLOSE;
                }
            }
            case WAIT_CLOSE -> {
                if (screen == null && readOnlyScreenObserved && letterEditClicked && deathNoteClicked) {
                    writeMarker(markerPath(), player);
                    phase = Phase.DONE;
                }
            }
            default -> { }
        }
    }

    private static Path markerPath() {
        String configured = System.getProperty("blindbox.ci.p4TextMarker");
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    /** 阶段旗标只安排 ciTest 输入时序；不是成功 marker，也不替代任何服务端业务校验。 */
    private static boolean scenarioEnabled() {
        String configured = System.getProperty("blindbox.ci.p4TextStageDir");
        return configured != null && !configured.isBlank()
                && Files.isRegularFile(Path.of(configured).toAbsolutePath().resolve("p4-text-enabled.flag"));
    }

    private static void observeBobDeath(Minecraft minecraft, LocalPlayer player) {
        String configured = System.getProperty("blindbox.ci.p4DeathMarker");
        if (bobDeathWritten || configured == null || configured.isBlank() || !(minecraft.screen instanceof DeathScreen)) return;
        Path marker = Path.of(configured).toAbsolutePath();
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("P4 死亡 marker 缺少父目录");
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\nobserver_uuid=" + player.getUUID() + "\ndeath_screen_observed=true\n", StandardCharsets.UTF_8);
            bobDeathWritten = true;
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 P4 真实死亡界面 marker", exception);
        }
    }

    private static void writeMarker(Path marker, LocalPlayer player) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("P4 文本 marker 缺少父目录");
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\n"
                    + "observer_uuid=" + player.getUUID() + "\n"
                    + "read_only_screen_observed=true\n"
                    + "letter_edit_clicked=true\n"
                    + "death_note_clicked=true\n"
                    + "normal_use_key_injected=true\n"
                    + "sneak_use_key_injected=true\n"
                    + "server_close_observed=true\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 P4 真实 GUI marker", exception);
        }
    }
}
