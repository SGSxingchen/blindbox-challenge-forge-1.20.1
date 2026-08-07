package cn.blindboxchallenge.citest.client.audio;

import cn.blindboxchallenge.client.audio.RemoteAudioDownload;
import cn.blindboxchallenge.client.audio.RemoteMusicSoundInstance;
import cn.blindboxchallenge.citest.CiTestProbe;
import cn.blindboxchallenge.citest.P4MusicCiScenario;
import cn.blindboxchallenge.client.MusicBoxScreen;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.event.MusicBoxPlaybackFailedEvent;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 只放入独立 ciTest Jar 的八音盒客户端观察器。Alice 经真实按键/生产 GUI 配置和播放；两个客户端
 * 仅在生产 SoundEngine 真正读取到非空 PCM 后写 marker，绝不直调网络包、下载器或 AudioStream.read。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientMusicBoxObservation {
    private static final int RELEASE_SHIFT_TICKS = 4;
    /** 仅在真实配置入口长期不可用时记录一次本地事实，绝不把该文件当作配置或播放成功。 */
    private static final int CONFIGURATION_INPUT_STALL_TICKS = 100;
    private enum AlicePhase { WAIT_OGG_OPEN, WAIT_OGG_SCREEN, WAIT_OGG_PLAY, WAIT_OGG_READ, WAIT_CACHE_FLAG, WAIT_CACHE_PLAY,
        WAIT_CACHE_READ, WAIT_MP3_SNEAK, WAIT_MP3_SCREEN, WAIT_MP3_PLAY, WAIT_MP3_READ, WAIT_NETWORK_FLAG, WAIT_BROKEN_TRIGGER, WAIT_BROKEN_SNEAK,
        WAIT_BROKEN_SCREEN, WAIT_BROKEN_PLAY, WAIT_BROKEN_FAILURE, COMPLETE }

    private static final Set<UUID> RECEIVED_EVENTS = new HashSet<>();
    private static final Map<UUID, String> EVENT_URLS = new ConcurrentHashMap<>();
    private static volatile AlicePhase alicePhase = AlicePhase.WAIT_OGG_OPEN;
    private static int sneakTicks;
    private static volatile boolean failureObserved;
    private static volatile Object connectionAtFailure;
    private static volatile boolean disconnectedAfterFailure;
    private static volatile boolean reconnectedAfterFailure;
    private static volatile boolean replayAfterReconnect;
    private static int noReplayTicks;
    private static boolean noReplayWritten;
    private static boolean oggEditorReopenedWritten;
    private static AlicePhase shiftReleasePhase;
    private static int releasedShiftTicks;
    private static int oggOpenWaitTicks;
    private static boolean oggConfigurationStallWritten;

    private CiClientMusicBoxObservation() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void receivedPlayback(MusicBoxPlaybackEvent event) {
        // P5 压力八音盒拥有独立的输入器和 PCM marker；P4 观察器不能抢占其生产声音包装，
        // 更不能把后来事件算进 P4 断线重连的历史集合。
        if (!isP4Url(event.url())) return;
        RECEIVED_EVENTS.add(event.eventId());
        EVENT_URLS.put(event.eventId(), event.url());
        // 仅证明收到生产 S2C，绝不代表下载、解码或 PCM read 成功。
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Path directory = markerDirectory();
        if (player != null && directory != null) {
            writeNewMarker(directory.resolve(markerPrefix(player) + "received-" + event.eventId() + ".marker"), "schema=1\n"
                    + "observer_uuid=" + player.getUUID() + "\n"
                    + "event_uuid=" + event.eventId() + "\n"
                    + "url=" + event.url() + "\n"
                    + "source=" + position(event.source()) + "\n"
                    + "production_s2c_received=true\n");
        }
        if (reconnectedAfterFailure || (disconnectedAfterFailure && Minecraft.getInstance().getConnection() != connectionAtFailure)) {
            replayAfterReconnect = true;
            if (minecraft.player != null && !isAlice(minecraft.player) && markerDirectory() != null) {
                writeNewMarker(markerDirectory().resolve(markerPrefix(minecraft.player) + "replay-detected.marker"), "schema=1\n"
                        + "observer_uuid=" + minecraft.player.getUUID() + "\n"
                        + "event_uuid=" + event.eventId() + "\n"
                        + "url=" + event.url() + "\n"
                        + "replay_detected_after_reconnect=true\n");
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void failedPlayback(MusicBoxPlaybackFailedEvent event) {
        if (!isP4Url(event.url())) return;
        Path directory = markerDirectory();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (directory == null || player == null || !RECEIVED_EVENTS.contains(event.eventId())) return;
        // 非预期失败也必须留定位证据；此文件不参与任何成功或预期坏文件断言。
        writeNewMarker(directory.resolve(markerPrefix(player) + "diagnostic-failed-" + event.eventId() + ".marker"), "schema=1\n"
                + "observer_uuid=" + player.getUUID() + "\n"
                + "event_uuid=" + event.eventId() + "\n"
                + "url=" + event.url() + "\n"
                + "source=" + position(event.source()) + "\n"
                + "production_failure_observed=true\n");
        if (!event.url().endsWith("blindbox-ci-broken.ogg")) return;
        writeNewMarker(directory.resolve(markerPrefix(player) + "failed-" + event.eventId() + ".marker"), "schema=1\n"
                + "observer_uuid=" + player.getUUID() + "\n"
                + "event_uuid=" + event.eventId() + "\n"
                + "url=" + event.url() + "\n"
                + "source=" + position(event.source()) + "\n"
                + "s2c_observed=true\n"
                + "download_or_decode_failed=true\n");
        failureObserved = true;
        connectionAtFailure = minecraft.getConnection();
        if (isAlice(player)) alicePhase = AlicePhase.COMPLETE;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void wrapProductionMusicStream(PlaySoundEvent event) {
        if (!(event.getOriginalSound() instanceof RemoteMusicSoundInstance remote)) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Path directory = markerDirectory();
        if (player == null || directory == null || !RECEIVED_EVENTS.contains(remote.eventId())) return;
        event.setSound(new ObservedSoundInstance(remote, directory, player.getUUID(), markerPrefix(player)));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) {
            if (failureObserved && connectionAtFailure != null) disconnectedAfterFailure = true;
            return;
        }
        if (failureObserved && disconnectedAfterFailure && minecraft.getConnection() != connectionAtFailure && !replayAfterReconnect) {
            reconnectedAfterFailure = true;
            if (!isAlice(player) && ++noReplayTicks >= 80 && !noReplayWritten) {
                writeNewMarker(markerDirectory().resolve(markerPrefix(player) + "no-replay.marker"), "schema=1\n"
                        + "observer_uuid=" + player.getUUID() + "\n"
                        + "reconnected=true\n"
                        + "prior_event_count=" + RECEIVED_EVENTS.size() + "\n"
                        + "no_replayed_event_for_80_ticks=true\n");
                noReplayWritten = true;
            }
        }
        if (!isAlice(player) || markerDirectory() == null) return;
        if (alicePhase == AlicePhase.WAIT_OGG_READ && minecraft.screen instanceof MusicBoxScreen && !oggEditorReopenedWritten) {
            // 真实普通右键后若又收到编辑屏，说明服务端并未进入播放分支；这不是成功 marker。
            writeNewMarker(markerDirectory().resolve(markerPrefix(player) + "ogg-editor-reopened.marker"), "schema=1\n"
                    + "observer_uuid=" + player.getUUID() + "\n"
                    + "screen_reopened_after_play_attempt=true\n");
            oggEditorReopenedWritten = true;
        }
        driveAlice(minecraft, player);
    }

    private static void driveAlice(Minecraft minecraft, LocalPlayer player) {
        Screen screen = minecraft.screen;
        switch (alicePhase) {
            case WAIT_OGG_OPEN -> {
                // 观察到服务器实际放置的生产方块前，不得写任何 p4-music 定位文件；否则启动前的
                // 客户端空闲状态会被服务端正确当成旧 marker 拒绝，且根本不是本场景的输入事实。
                if (!observesMusicBox(minecraft)) return;
                if (canUseMusicBox(minecraft, player)) {
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    // 只证明真实 use 键已注入客户端队列，不能代替菜单、C2S、S2C 或 PCM 成功。
                    writeNewMarker(markerDirectory().resolve(markerPrefix(player) + "configuration-open-attempt.marker"),
                            configurationInputFacts(minecraft, player, true));
                    alicePhase = AlicePhase.WAIT_OGG_SCREEN;
                } else if (++oggOpenWaitTicks >= CONFIGURATION_INPUT_STALL_TICKS && !oggConfigurationStallWritten) {
                    // 已完成定位、同步与输入前置的有限等待仍不可用时才记录一次；不触碰 phase 或输入。
                    writeNewMarker(markerDirectory().resolve(markerPrefix(player) + "configuration-input-stalled.marker"),
                            configurationInputFacts(minecraft, player, false));
                    oggConfigurationStallWritten = true;
                }
            }
            case WAIT_OGG_SCREEN -> submitUrl(screen, oggUrl(), AlicePhase.WAIT_OGG_PLAY, minecraft);
            case WAIT_OGG_PLAY -> {
                if (useConfiguredMusicBox(minecraft, player)) alicePhase = AlicePhase.WAIT_OGG_READ;
            }
            case WAIT_CACHE_FLAG -> {
                if (Files.isRegularFile(markerDirectory().resolve("p4-music-cache-enabled.flag"))) alicePhase = AlicePhase.WAIT_CACHE_PLAY;
            }
            case WAIT_CACHE_PLAY -> {
                if (useConfiguredMusicBox(minecraft, player)) alicePhase = AlicePhase.WAIT_CACHE_READ;
            }
            case WAIT_MP3_SNEAK -> continueSneak(minecraft, AlicePhase.WAIT_MP3_SCREEN);
            case WAIT_MP3_SCREEN -> submitUrl(screen, mp3Url(), AlicePhase.WAIT_MP3_PLAY, minecraft);
            case WAIT_MP3_PLAY -> {
                if (useConfiguredMusicBox(minecraft, player)) alicePhase = AlicePhase.WAIT_MP3_READ;
            }
            case WAIT_NETWORK_FLAG -> {
                if (Files.isRegularFile(markerDirectory().resolve("p4-music-network-restored.flag"))) beginSneak(minecraft, AlicePhase.WAIT_MP3_SNEAK);
            }
            case WAIT_BROKEN_SNEAK -> continueSneak(minecraft, AlicePhase.WAIT_BROKEN_SCREEN);
            case WAIT_BROKEN_SCREEN -> submitUrl(screen, brokenUrl(), AlicePhase.WAIT_BROKEN_PLAY, minecraft);
            case WAIT_BROKEN_PLAY -> {
                if (useConfiguredMusicBox(minecraft, player)) alicePhase = AlicePhase.WAIT_BROKEN_FAILURE;
            }
            case WAIT_BROKEN_TRIGGER -> beginSneak(minecraft, AlicePhase.WAIT_BROKEN_SNEAK);
            default -> { }
        }
    }

    private static boolean useConfiguredMusicBox(Minecraft minecraft, LocalPlayer player) {
        if (!canUseMusicBox(minecraft, player)) return false;
        // 普通播放必须显式释放潜行键；这仍是客户端真实输入，不伪造服务端 Block#use 或 S2C。
        KeyMapping.set(minecraft.options.keyShift.getKey(), false);
        if (shiftReleasePhase != alicePhase) {
            shiftReleasePhase = alicePhase;
            releasedShiftTicks = 0;
        }
        // 必须让真实客户端至少处理四个 tick 的“已释放潜行”状态，再注入普通右键；否则服务端
        // 可能仍消费上一个潜行包并重开编辑器，不能把该网络同步竞态误判为音频下载失败。
        if (++releasedShiftTicks < RELEASE_SHIFT_TICKS) return false;
        KeyMapping.click(minecraft.options.keyUse.getKey());
        // 注入真实 use 键不代表服务端消费、S2C 或播放成功，只用于区分输入与后续链路。
        Path directory = markerDirectory();
        if (directory != null && alicePhase == AlicePhase.WAIT_OGG_PLAY) {
            BlockPos target = minecraft.level.getSharedSpawnPos().offset(P4MusicCiScenario.MUSIC_BOX_OFFSET);
            writeNewMarker(directory.resolve(markerPrefix(player) + "use-attempt.marker"), "schema=1\n"
                    + "observer_uuid=" + player.getUUID() + "\n"
                    + "target=" + position(target) + "\n"
                    + "standing=" + player.blockPosition() + "\n"
                    + "pitch=" + player.getXRot() + "\n"
                    + "key_use_injected=true\n");
        }
        return true;
    }

    private static void beginSneak(Minecraft minecraft, AlicePhase next) {
        KeyMapping.set(minecraft.options.keyShift.getKey(), true);
        sneakTicks = 0;
        alicePhase = next;
    }

    private static void continueSneak(Minecraft minecraft, AlicePhase screenPhase) {
        if (++sneakTicks < 4) return;
        KeyMapping.click(minecraft.options.keyUse.getKey());
        alicePhase = screenPhase;
    }

    private static void submitUrl(Screen screen, String url, AlicePhase next, Minecraft minecraft) {
        if (!(screen instanceof MusicBoxScreen)) return;
        KeyMapping.set(minecraft.options.keyShift.getKey(), false);
        int left = (screen.width - 286) / 2;
        int top = (screen.height - 116) / 2;
        screen.mouseClicked(left + 20.0D, top + 48.0D, 0);
        clearExistingUrl(screen);
        for (char character : url.toCharArray()) screen.charTyped(character, 0);
        screen.mouseClicked(left + 142.0D, top + 86.0D, 0);
        screen.mouseReleased(left + 142.0D, top + 86.0D, 0);
        alicePhase = next;
    }

    /**
     * Screen 的 Ctrl 状态来自真实键盘状态，不能把带修饰参数的 {@code keyPressed} 当作可靠全选。
     * 这里仍通过已聚焦的生产 EditBox 的 End/Backspace 输入路径逐项清空，随后才用同一 Screen 的
     * {@code charTyped} 输入新 URL；不接触菜单字段、C2S 包或服务端状态。
     */
    private static void clearExistingUrl(Screen screen) {
        screen.keyPressed(InputConstants.KEY_END, 0, 0);
        for (int index = 0; index < AudioUrlPolicy.MAX_URL_LENGTH; index++) {
            screen.keyPressed(InputConstants.KEY_BACKSPACE, 0, 0);
        }
    }

    private static boolean canUseMusicBox(Minecraft minecraft, LocalPlayer player) {
        BlockPos target = minecraft.level.getSharedSpawnPos().offset(P4MusicCiScenario.MUSIC_BOX_OFFSET);
        return minecraft.screen == null && player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
                && player.blockPosition().equals(target.above()) && player.getXRot() >= 80.0F;
    }

    /** 只以客户端已同步的生产方块启动输入诊断；服务端仍独立校验 use、菜单和 C2S。 */
    private static boolean observesMusicBox(Minecraft minecraft) {
        BlockPos target = minecraft.level.getSharedSpawnPos().offset(P4MusicCiScenario.MUSIC_BOX_OFFSET);
        return minecraft.level.getBlockState(target).is(ModBlocks.MUSIC_BOX.get());
    }

    /** 仅记录无敏感的客户端输入前置；不写 URL、服务器地址、路径、会话或成功字段。 */
    private static String configurationInputFacts(Minecraft minecraft, LocalPlayer player, boolean keyUseInjected) {
        BlockPos target = minecraft.level.getSharedSpawnPos().offset(P4MusicCiScenario.MUSIC_BOX_OFFSET);
        Screen currentScreen = minecraft.screen;
        String screen = currentScreen == null ? "none" : currentScreen.getClass().getSimpleName();
        if (screen.isBlank()) screen = "unnamed";
        return "schema=1\n"
                + "alice_phase=" + alicePhase + "\n"
                + "screen=" + screen + "\n"
                + "main_hand_empty=" + player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() + "\n"
                + "standing=" + position(player.blockPosition()) + "\n"
                + "target=" + position(target) + "\n"
                + "pitch=" + player.getXRot() + "\n"
                + "music_box_observed=" + observesMusicBox(minecraft) + "\n"
                + "can_use_music_box=" + canUseMusicBox(minecraft, player) + "\n"
                + "key_use_injected=" + keyUseInjected + "\n";
    }

    private static void observedRead(RemoteMusicSoundInstance remote, Path directory, UUID observer, String prefix, int bytes) {
        writeNewMarker(directory.resolve(prefix + "read-" + remote.eventId() + ".marker"), "schema=1\n"
                + "observer_uuid=" + observer + "\n"
                + "event_uuid=" + remote.eventId() + "\n"
                + "url=" + EVENT_URLS.getOrDefault(remote.eventId(), "") + "\n"
                + "kind=" + remote.kind().name() + "\n"
                + "cache_hit=" + remote.cacheHit() + "\n"
                + "source=" + position(remote.source()) + "\n"
                + "pcm_bytes=" + bytes + "\n"
                + "s2c_observed=true\n");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && isAlice(minecraft.player)) {
            if (remote.kind() == RemoteAudioDownload.Kind.OGG && !remote.cacheHit()) alicePhase = AlicePhase.WAIT_CACHE_FLAG;
            else if (remote.kind() == RemoteAudioDownload.Kind.OGG) alicePhase = AlicePhase.WAIT_NETWORK_FLAG;
            else if (remote.kind() == RemoteAudioDownload.Kind.MP3) alicePhase = AlicePhase.WAIT_BROKEN_TRIGGER;
        }
    }

    private static Path markerDirectory() {
        String configured = System.getProperty("blindbox.ci.p4MusicMarkerDir");
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    private static String audioBase() {
        String configured = System.getProperty("blindbox.ci.p4AudioBase");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 blindbox.ci.p4AudioBase");
        return configured.endsWith("/") ? configured : configured + "/";
    }
    private static String oggUrl() { return audioBase() + "blindbox-ci-tone.ogg"; }
    private static String mp3Url() { return audioBase() + "blindbox-ci-tone.mp3"; }
    private static String brokenUrl() { return audioBase() + "blindbox-ci-broken.ogg"; }
    /**
     * P4 和 P5 以独立真实客户端进程运行。P5 进程未配置 P4 基址时，P4 观察器只是非目标事件的
     * 旁观者，绝不能在生产 S2C 分发前抛错并阻断 P5 的声音包装；真正进入 P4 场景仍由 audioBase()
     * 严格要求该属性存在。
     */
    private static boolean isP4Url(String url) {
        String configured = System.getProperty("blindbox.ci.p4AudioBase");
        if (url == null || configured == null || configured.isBlank()) return false;
        String base = configured.endsWith("/") ? configured : configured + "/";
        return (base + "blindbox-ci-tone.ogg").equals(url) || (base + "blindbox-ci-tone.mp3").equals(url)
                || (base + "blindbox-ci-broken.ogg").equals(url);
    }
    private static boolean isAlice(LocalPlayer player) { return "BlindBoxAlice".equals(player.getGameProfile().getName()); }
    private static String markerPrefix(LocalPlayer player) { return isAlice(player) ? "client-1-p4-music-" : "client-2-p4-music-"; }
    private static String position(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

    private static void writeNewMarker(Path marker, String value) {
        try {
            Files.createDirectories(marker.getParent());
            Path temporary = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".part");
            try {
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, marker);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入真实八音盒客户端 marker：" + marker, exception);
        }
    }

    private record ObservedSoundInstance(RemoteMusicSoundInstance delegate, Path directory, UUID observer, String prefix) implements SoundInstance {
        @Override public net.minecraft.resources.ResourceLocation getLocation() { return ((SoundInstance) delegate).getLocation(); }
        @Override public net.minecraft.client.sounds.WeighedSoundEvents resolve(net.minecraft.client.sounds.SoundManager manager) { return ((SoundInstance) delegate).resolve(manager); }
        @Override public Sound getSound() { return ((SoundInstance) delegate).getSound(); }
        @Override public net.minecraft.sounds.SoundSource getSource() { return ((SoundInstance) delegate).getSource(); }
        @Override public boolean isLooping() { return ((SoundInstance) delegate).isLooping(); }
        @Override public boolean isRelative() { return ((SoundInstance) delegate).isRelative(); }
        @Override public int getDelay() { return ((SoundInstance) delegate).getDelay(); }
        @Override public float getVolume() { return ((SoundInstance) delegate).getVolume(); }
        @Override public float getPitch() { return ((SoundInstance) delegate).getPitch(); }
        @Override public double getX() { return ((SoundInstance) delegate).getX(); }
        @Override public double getY() { return ((SoundInstance) delegate).getY(); }
        @Override public double getZ() { return ((SoundInstance) delegate).getZ(); }
        @Override public Attenuation getAttenuation() { return ((SoundInstance) delegate).getAttenuation(); }
        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return delegate.getStream(buffers, sound, looping).thenApply(stream -> new ObservedAudioStream(stream, delegate, directory, observer, prefix));
        }
    }

    private record ObservedAudioStream(AudioStream delegate, RemoteMusicSoundInstance remote, Path directory, UUID observer,
                                       String prefix) implements AudioStream {
        private static final Set<UUID> WRITTEN_EVENTS = new HashSet<>();
        @Override public AudioFormat getFormat() { return delegate.getFormat(); }
        @Override public ByteBuffer read(int requestedBytes) throws IOException {
            ByteBuffer pcm = delegate.read(requestedBytes);
            if (pcm.hasRemaining() && markWritten(remote.eventId())) observedRead(remote, directory, observer, prefix, pcm.remaining());
            return pcm;
        }
        @Override public void close() throws IOException { delegate.close(); }
        private static synchronized boolean markWritten(UUID eventId) { return WRITTEN_EVENTS.add(eventId); }
    }
}
