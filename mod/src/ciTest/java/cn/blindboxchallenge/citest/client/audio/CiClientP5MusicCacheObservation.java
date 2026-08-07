package cn.blindboxchallenge.citest.client.audio;

import cn.blindboxchallenge.client.MusicBoxScreen;
import cn.blindboxchallenge.client.audio.RemoteAudioDownload;
import cn.blindboxchallenge.client.audio.RemoteMusicSoundInstance;
import cn.blindboxchallenge.citest.CiTestProbe;
import cn.blindboxchallenge.citest.P5MusicCacheCiScenario;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 独立 ciTest Jar 的 P5 压力输入器/观察器。Alice 只走生产 GUI 和真实 use 键；两个客户端只有在
 * SoundEngine 从生产 AudioStream 读到 PCM 后才能写结果。它绝不调用下载器、播放服务、网络包或 SoundManager。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientP5MusicCacheObservation {
    private static final int RELEASE_SHIFT_TICKS = 4;
    private static final Map<UUID, String> RECEIVED_EVENTS = new ConcurrentHashMap<>();
    private static final Set<UUID> WRITTEN_EVENTS = ConcurrentHashMap.newKeySet();
    private enum State { WAIT_ENABLED, CONFIGURE_SNEAK, CONFIGURE_SCREEN, PLAY, SINGLE_SECOND_USE, WAIT_PCM, WAIT_NEXT, WAIT_CORRUPTION, COMPLETE }
    private static volatile State state = State.WAIT_ENABLED;
    private static volatile int request;
    private static int shiftTicks;
    private static int releaseTicks;
    private static final AtomicInteger SINGLE_FLIGHT_READS = new AtomicInteger();
    private static boolean corruptionWritten;

    private CiClientP5MusicCacheObservation() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void receivedPlayback(MusicBoxPlaybackEvent event) {
        if (!isPressureUrl(event.url())) return;
        RECEIVED_EVENTS.put(event.eventId(), event.url());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void wrapProductionMusicStream(PlaySoundEvent event) {
        if (!(event.getOriginalSound() instanceof RemoteMusicSoundInstance remote) || !isPressureUrl(RECEIVED_EVENTS.get(remote.eventId()))) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Path directory = markerDirectory();
        if (player == null || directory == null || !remote.source().equals(target(minecraft))) return;
        event.setSound(new ObservedSoundInstance(remote, directory, player.getUUID(), markerPrefix(player)));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !isAlice(player) || markerDirectory() == null) return;
        driveAlice(minecraft, player);
    }

    private static void driveAlice(Minecraft minecraft, LocalPlayer player) {
        switch (state) {
            case WAIT_ENABLED -> {
                if (stage("p5-music-cache-enabled.flag")) beginConfigure(1, minecraft);
            }
            case CONFIGURE_SNEAK -> {
                if (++shiftTicks < RELEASE_SHIFT_TICKS) return;
                KeyMapping.click(minecraft.options.keyUse.getKey());
                state = State.CONFIGURE_SCREEN;
            }
            case CONFIGURE_SCREEN -> submitUrl(minecraft.screen, currentUrl(), minecraft);
            case PLAY -> {
                if (!canUseMusicBox(minecraft, player)) return;
                KeyMapping.set(minecraft.options.keyShift.getKey(), false);
                if (++releaseTicks < RELEASE_SHIFT_TICKS) return;
                KeyMapping.click(minecraft.options.keyUse.getKey());
                state = request == 7 ? State.SINGLE_SECOND_USE : State.WAIT_PCM;
            }
            case SINGLE_SECOND_USE -> {
                // 第二次仍是生产普通右键：不等第一次 PCM，令两条 CLIENT_AUDIO 任务并发争用同一 URL 的 IN_FLIGHT。
                if (!canUseMusicBox(minecraft, player)) return;
                KeyMapping.click(minecraft.options.keyUse.getKey());
                state = State.WAIT_PCM;
            }
            case WAIT_NEXT -> advanceWhenServerAllows(minecraft);
            case WAIT_CORRUPTION -> {
                if (!corruptionWritten && injectActualCachedFileCorruption(minecraft, player)) corruptionWritten = true;
                if (corruptionWritten && stage("p5-music-cache-corrupt-retry.flag")) beginConfigure(8, minecraft);
            }
            default -> { }
        }
    }

    private static void advanceWhenServerAllows(Minecraft minecraft) {
        if (request >= 1 && request < P5MusicCacheCiScenario.PRESSURE_ROUNDS && stage("p5-music-cache-fill-" + (request + 1) + ".flag")) {
            beginConfigure(request + 1, minecraft);
        } else if (request == P5MusicCacheCiScenario.PRESSURE_ROUNDS && stage("p5-music-cache-eviction-reload.flag")) {
            beginConfigure(6, minecraft);
        } else if (request == 6 && stage("p5-music-cache-singleflight.flag")) {
            beginConfigure(7, minecraft);
        }
    }

    private static void beginConfigure(int nextRequest, Minecraft minecraft) {
        if (!observesMusicBox(minecraft)) return;
        request = nextRequest;
        SINGLE_FLIGHT_READS.set(0);
        KeyMapping.set(minecraft.options.keyShift.getKey(), true);
        shiftTicks = 0;
        releaseTicks = 0;
        state = State.CONFIGURE_SNEAK;
    }

    private static void submitUrl(Screen screen, String url, Minecraft minecraft) {
        if (!(screen instanceof MusicBoxScreen)) return;
        KeyMapping.set(minecraft.options.keyShift.getKey(), false);
        int left = (screen.width - 286) / 2;
        int top = (screen.height - 116) / 2;
        screen.mouseClicked(left + 20.0D, top + 48.0D, 0);
        screen.keyPressed(InputConstants.KEY_END, 0, 0);
        for (int index = 0; index < AudioUrlPolicy.MAX_URL_LENGTH; index++) screen.keyPressed(InputConstants.KEY_BACKSPACE, 0, 0);
        for (char character : url.toCharArray()) screen.charTyped(character, 0);
        screen.mouseClicked(left + 142.0D, top + 86.0D, 0);
        screen.mouseReleased(left + 142.0D, top + 86.0D, 0);
        releaseTicks = 0;
        state = State.PLAY;
    }

    private static void observedRead(RemoteMusicSoundInstance remote, Path directory, UUID observer, String prefix, int bytes) {
        if (!WRITTEN_EVENTS.add(remote.eventId())) return;
        writeNewMarker(directory.resolve(prefix + "read-" + remote.eventId() + ".marker"), "schema=1\n"
                + "observer_uuid=" + observer + "\n"
                + "event_uuid=" + remote.eventId() + "\n"
                + "url=" + RECEIVED_EVENTS.getOrDefault(remote.eventId(), "") + "\n"
                + "kind=" + remote.kind().name() + "\n"
                + "cache_hit=" + remote.cacheHit() + "\n"
                + "single_flight_follower=" + remote.singleFlightFollower() + "\n"
                + "source=" + position(remote.source()) + "\n"
                + "pcm_bytes=" + bytes + "\n"
                + "s2c_observed=true\n");
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // 两个客户端都必须在自己的同 URL 双事件均经 SoundEngine 读取 PCM 后，才截断各自真实的
        // fill-5 缓存并写事实 marker；Bob 不驱动 GUI，却不能被降格为 Alice 的替身。
        if (singleFlightUrl().equals(RECEIVED_EVENTS.get(remote.eventId())) && SINGLE_FLIGHT_READS.incrementAndGet() == 2) {
            if (!corruptionWritten && player != null && injectActualCachedFileCorruption(minecraft, player)) corruptionWritten = true;
            if (isAlice(player)) state = State.WAIT_CORRUPTION;
        }
        if (!isAlice(player)) return;
        if (request == 7) {
            // 第一次单飞 PCM 不能放行下一阶段；第二次会在上面的 per-JVM 计数后转入损坏步骤。
        } else if (state == State.WAIT_PCM) {
            state = request == 8 ? State.COMPLETE : State.WAIT_NEXT;
        }
    }

    /** 只破坏该客户端自己经真实 PCM 验证后的 fill-5 文件；下一轮仍必须由生产 GUI 驱动重新下载。 */
    private static boolean injectActualCachedFileCorruption(Minecraft minecraft, LocalPlayer player) {
        try {
            String expectedUrl = fillUrl(P5MusicCacheCiScenario.PRESSURE_ROUNDS);
            String hash = hex(MessageDigest.getInstance("SHA-256").digest(AudioUrlPolicy.normalizeHttpsUrl(expectedUrl)
                    .getBytes(StandardCharsets.UTF_8)));
            Path cache = minecraft.gameDirectory.toPath().resolve("blindboxchallenge-audio-cache");
            Path file;
            try (var files = Files.list(cache)) {
                file = files.filter(path -> path.getFileName().toString().startsWith(hash + "-") && path.getFileName().toString().endsWith(".ogg"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("P5 未找到已真实下载的 fill-5 缓存文件"));
            }
            long original = Files.size(file);
            if (original <= 13L * 1024L * 1024L) throw new IllegalStateException("P5 fill-5 缓存大小不足压力下限");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(64L); }
            long truncated = Files.size(file);
            if (truncated != 64L) throw new IllegalStateException("P5 损坏缓存截断长度不正确");
            writeNewMarker(markerDirectory().resolve(markerPrefix(player) + "corrupted.marker"), "schema=1\n"
                    + "observer_uuid=" + player.getUUID() + "\n"
                    + "url=" + expectedUrl + "\n"
                    + "original_bytes=" + original + "\n"
                    + "truncated_bytes=" + truncated + "\n"
                    + "corruption_injected=true\n");
            return true;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法执行 P5 已验证缓存损坏操作", exception);
        }
    }

    private static boolean canUseMusicBox(Minecraft minecraft, LocalPlayer player) {
        BlockPos target = target(minecraft);
        return minecraft.screen == null && player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() && player.blockPosition().equals(target.above())
                && player.getXRot() >= 80.0F && observesMusicBox(minecraft);
    }
    private static boolean observesMusicBox(Minecraft minecraft) { return minecraft.level != null && minecraft.level.getBlockState(target(minecraft)).is(ModBlocks.MUSIC_BOX.get()); }
    private static BlockPos target(Minecraft minecraft) { return minecraft.level.getSharedSpawnPos().offset(P5MusicCacheCiScenario.MUSIC_BOX_OFFSET); }
    private static boolean stage(String name) { Path directory = markerDirectory(); return directory != null && Files.isRegularFile(directory.resolve(name)); }
    private static String currentUrl() { return request == 7 ? singleFlightUrl() : request == 6 ? fillUrl(1) : fillUrl(request == 8 ? P5MusicCacheCiScenario.PRESSURE_ROUNDS : request); }
    private static String audioBase() {
        String value = System.getProperty("blindbox.ci.p4AudioBase");
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少 blindbox.ci.p4AudioBase");
        return value.endsWith("/") ? value : value + "/";
    }
    private static String fillUrl(int round) { return audioBase() + "blindbox-ci-cache-pressure.ogg?ci=p5-fill-" + round; }
    private static String singleFlightUrl() { return audioBase() + "blindbox-ci-cache-pressure.ogg?ci=p5-singleflight"; }
    private static boolean isPressureUrl(String url) { return url != null && (url.equals(singleFlightUrl()) || java.util.stream.IntStream.rangeClosed(1, P5MusicCacheCiScenario.PRESSURE_ROUNDS).anyMatch(round -> url.equals(fillUrl(round)))); }
    private static boolean isAlice(LocalPlayer player) { return player != null && "BlindBoxAlice".equals(player.getGameProfile().getName()); }
    private static String markerPrefix(LocalPlayer player) { return isAlice(player) ? "client-1-p5-music-cache-" : "client-2-p5-music-cache-"; }
    private static String position(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static Path markerDirectory() {
        String value = System.getProperty("blindbox.ci.p5MusicCacheMarkerDir");
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath();
    }

    private static void writeNewMarker(Path marker, String value) {
        try {
            Files.createDirectories(marker.getParent());
            Path temporary = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".part");
            try {
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                try { Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, marker); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 P5 八音盒缓存真实客户端 marker：" + marker, exception);
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

    private record ObservedAudioStream(AudioStream delegate, RemoteMusicSoundInstance remote, Path directory, UUID observer, String prefix) implements AudioStream {
        @Override public AudioFormat getFormat() { return delegate.getFormat(); }
        @Override public ByteBuffer read(int requestedBytes) throws IOException {
            ByteBuffer pcm = delegate.read(requestedBytes);
            if (pcm.hasRemaining()) {
                observedRead(remote, directory, observer, prefix, pcm.remaining());
                // 夹具已由生产 SoundEngine 请求并取得真实 PCM；受控压力不应让 152 秒测试噪声长期占住两条生产槽。
                delegate.close();
            }
            return pcm;
        }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
}
