package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.registry.ModBlocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** P4 八音盒真实双客户端场景：服务端只布置方块、读取生产 BE 和交叉核验真实客户端 PCM marker。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P4MusicCiScenario {
    public static final BlockPos MUSIC_BOX_OFFSET = new BlockPos(32, 160, 32);
    private static ActiveScenario active;

    private P4MusicCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P4 八音盒场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_MUSIC_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P4 music scenario", exception);
            source.sendFailure(Component.literal("CI P4 八音盒场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P4 八音盒场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_MUSIC_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P4 music scenario", exception);
            source.sendFailure(Component.literal("CI P4 八音盒清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    private enum Phase { WAIT_OGG_CONFIGURATION, WAIT_OGG_FIRST, WAIT_OGG_CACHE, WAIT_MP3_CONFIGURATION,
        WAIT_MP3, WAIT_BROKEN_CONFIGURATION, WAIT_BROKEN_FAILURE, WAIT_NO_REPLAY, WAIT_NO_REPLAY_GRACE, READY, FAILED }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final ServerPlayer alice;
        private final UUID aliceId;
        private final UUID bobId;
        private final BlockPos position;
        private final BlockState previousState;
        private final ItemStack previousMainHand;
        private final BlockPos previousAlicePosition;
        private final float previousYaw;
        private final float previousPitch;
        private final Path markerDirectory;
        private final String baseUrl;
        private final long startedAt;
        private Phase phase = Phase.WAIT_OGG_CONFIGURATION;
        private UUID firstOggEvent;
        private UUID cachedOggEvent;
        private UUID mp3Event;
        private long noReplayGraceUntil = -1L;

        private ActiveScenario(MinecraftServer server, ServerLevel level, ServerPlayer alice, ServerPlayer bob, BlockPos position,
                               BlockState previousState, Path markerDirectory, String baseUrl) {
            this.server = server;
            this.level = level;
            this.alice = alice;
            this.aliceId = alice.getUUID();
            this.bobId = bob.getUUID();
            this.position = position;
            this.previousState = previousState;
            this.previousMainHand = alice.getMainHandItem().copy();
            this.previousAlicePosition = alice.blockPosition();
            this.previousYaw = alice.getYRot();
            this.previousPitch = alice.getXRot();
            this.markerDirectory = markerDirectory;
            this.baseUrl = baseUrl;
            this.startedAt = level.getGameTime();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            ServerPlayer alice = requiredPlayer(server, "BlindBoxAlice");
            ServerPlayer bob = requiredPlayer(server, "BlindBoxBob");
            ServerLevel level = server.overworld();
            BlockPos position = level.getSharedSpawnPos().offset(MUSIC_BOX_OFFSET);
            if (!level.getBlockState(position).isAir()) throw new IllegalStateException("P4 八音盒夹具位置不是空气");
            Path markerDirectory = markerDirectory();
            try (var entries = Files.list(markerDirectory)) {
                if (entries.anyMatch(path -> path.getFileName().toString().startsWith("client-")
                        && path.getFileName().toString().contains("p4-music-"))) {
                    throw new IllegalStateException("P4 八音盒客户端 marker 已存在，拒绝复用旧结果");
                }
            }
            String baseUrl = audioBaseUrl();
            ActiveScenario scenario = new ActiveScenario(server, level, alice, bob, position, level.getBlockState(position), markerDirectory, baseUrl);
            level.setBlock(position, ModBlocks.MUSIC_BOX.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(position) instanceof MusicBoxBlockEntity)) throw new IllegalStateException("P4 八音盒方块实体未创建");
            alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            alice.containerMenu.broadcastChanges();
            scenario.teleportAliceToMusicBox();
            return scenario;
        }

        private void tick() throws IOException {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            if (level.getGameTime() - startedAt > 3600L) throw new IllegalStateException("P4 八音盒真实场景超时：" + phase);
            MusicBoxBlockEntity box = box();
            switch (phase) {
                case WAIT_OGG_CONFIGURATION -> {
                    if (oggUrl().equals(box.url()) && box.revision() == 1) phase = Phase.WAIT_OGG_FIRST;
                }
                case WAIT_OGG_FIRST -> {
                    firstOggEvent = sharedReadEvent("OGG", false, null, oggUrl());
                    if (firstOggEvent != null) {
                        phase = Phase.WAIT_OGG_CACHE;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_MUSIC_OGG_FIRST=success");
                    }
                }
                case WAIT_OGG_CACHE -> {
                    if (!Files.isRegularFile(markerDirectory.resolve("p4-music-cache-enabled.flag"))) return;
                    cachedOggEvent = sharedReadEvent("OGG", true, firstOggEvent, oggUrl());
                    if (cachedOggEvent != null) {
                        phase = Phase.WAIT_MP3_CONFIGURATION;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_MUSIC_CACHE=success");
                    }
                }
                case WAIT_MP3_CONFIGURATION -> {
                    if (mp3Url().equals(box.url()) && box.revision() == 2) phase = Phase.WAIT_MP3;
                }
                case WAIT_MP3 -> {
                    mp3Event = sharedReadEvent("MP3", false, firstOggEvent, mp3Url());
                    if (mp3Event != null && !mp3Event.equals(cachedOggEvent)) {
                        phase = Phase.WAIT_BROKEN_CONFIGURATION;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_MUSIC_MP3=success");
                    }
                }
                case WAIT_BROKEN_CONFIGURATION -> {
                    if (brokenUrl().equals(box.url()) && box.revision() == 3) phase = Phase.WAIT_BROKEN_FAILURE;
                }
                case WAIT_BROKEN_FAILURE -> {
                    UUID failureEvent = sharedFailureEvent(brokenUrl());
                    if (failureEvent != null && !failureEvent.equals(firstOggEvent) && !failureEvent.equals(cachedOggEvent) && !failureEvent.equals(mp3Event)) {
                        phase = Phase.WAIT_NO_REPLAY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_MUSIC_FAILURE=success");
                    }
                }
                case WAIT_NO_REPLAY -> {
                    if (hasNoReplayMarker()) {
                        noReplayGraceUntil = level.getGameTime() + 40L;
                        phase = Phase.WAIT_NO_REPLAY_GRACE;
                    }
                }
                case WAIT_NO_REPLAY_GRACE -> {
                    if (Files.isRegularFile(markerDirectory.resolve("client-2-p4-music-replay-detected.marker"))) {
                        throw new IllegalStateException("Bob 重连后仍收到历史八音盒事件");
                    }
                    if (level.getGameTime() >= noReplayGraceUntil) {
                        phase = Phase.READY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_MUSIC_CLIENTS=success");
                    }
                }
                default -> { }
            }
        }

        private UUID sharedReadEvent(String kind, boolean cacheHit, UUID disallowed, String expectedUrl) throws IOException {
            List<Map<String, String>> aliceMarkers = readMarkers("client-1-p4-music-read-");
            List<Map<String, String>> bobMarkers = readMarkers("client-2-p4-music-read-");
            Map<UUID, Map<String, String>> aliceByEvent = matchingReads(aliceMarkers, aliceId, kind, cacheHit, disallowed, expectedUrl, "Alice");
            Map<UUID, Map<String, String>> bobByEvent = matchingReads(bobMarkers, bobId, kind, cacheHit, disallowed, expectedUrl, "Bob");
            for (UUID eventId : aliceByEvent.keySet()) if (bobByEvent.containsKey(eventId)) return eventId;
            return null;
        }

        private Map<UUID, Map<String, String>> matchingReads(List<Map<String, String>> markers, UUID observer, String kind, boolean cacheHit,
                                                               UUID disallowed, String expectedUrl, String label) {
            Map<UUID, Map<String, String>> matches = new HashMap<>();
            for (Map<String, String> marker : markers) {
                if (!"1".equals(marker.get("schema")) || !observer.toString().equals(marker.get("observer_uuid"))
                        || !kind.equals(marker.get("kind")) || !Boolean.toString(cacheHit).equals(marker.get("cache_hit"))
                        || !expectedUrl.equals(marker.get("url")) || !"true".equals(marker.get("s2c_observed"))
                        || !positionString().equals(marker.get("source"))) continue;
                int pcm = Integer.parseInt(marker.getOrDefault("pcm_bytes", "0"));
                UUID event = UUID.fromString(marker.getOrDefault("event_uuid", ""));
                if (pcm <= 0 || event.equals(disallowed) || matches.putIfAbsent(event, marker) != null) {
                    throw new IllegalStateException(label + " 八音盒 PCM marker 非法或重复");
                }
            }
            return matches;
        }

        private UUID sharedFailureEvent(String expectedUrl) throws IOException {
            Map<UUID, Map<String, String>> aliceMarkers = matchingFailures(readMarkers("client-1-p4-music-failed-"), aliceId, expectedUrl, "Alice");
            Map<UUID, Map<String, String>> bobMarkers = matchingFailures(readMarkers("client-2-p4-music-failed-"), bobId, expectedUrl, "Bob");
            for (UUID eventId : aliceMarkers.keySet()) if (bobMarkers.containsKey(eventId)) return eventId;
            return null;
        }

        private Map<UUID, Map<String, String>> matchingFailures(List<Map<String, String>> markers, UUID observer, String expectedUrl, String label) {
            Map<UUID, Map<String, String>> matches = new HashMap<>();
            for (Map<String, String> marker : markers) {
                if (!"1".equals(marker.get("schema")) || !observer.toString().equals(marker.get("observer_uuid"))
                        || !"true".equals(marker.get("s2c_observed")) || !"true".equals(marker.get("download_or_decode_failed"))
                        || !expectedUrl.equals(marker.get("url")) || !positionString().equals(marker.get("source"))) continue;
                UUID event = UUID.fromString(marker.getOrDefault("event_uuid", ""));
                if (matches.putIfAbsent(event, marker) != null) throw new IllegalStateException(label + " 八音盒失败 marker 重复");
            }
            return matches;
        }

        private boolean hasNoReplayMarker() throws IOException {
            Path marker = markerDirectory.resolve("client-2-p4-music-no-replay.marker");
            if (!Files.isRegularFile(marker)) return false;
            Map<String, String> values = parseMarker(marker);
            if (!"1".equals(values.get("schema")) || !bobId.toString().equals(values.get("observer_uuid"))
                    || !"true".equals(values.get("reconnected")) || Integer.parseInt(values.getOrDefault("prior_event_count", "0")) < 4
                    || !"true".equals(values.get("no_replayed_event_for_80_ticks"))) {
                throw new IllegalStateException("P4 八音盒重连不补播 marker 非法");
            }
            return true;
        }

        private List<Map<String, String>> readMarkers(String prefix) throws IOException {
            List<Map<String, String>> values = new ArrayList<>();
            try (var files = Files.list(markerDirectory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith(prefix)
                        && path.getFileName().toString().endsWith(".marker")).toList()) values.add(parseMarker(file));
            }
            return values;
        }

        private static Map<String, String> parseMarker(Path marker) throws IOException {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) throw new IllegalStateException("P4 八音盒 marker 格式非法：" + marker);
                if (values.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                    throw new IllegalStateException("P4 八音盒 marker 存在重复字段：" + marker);
                }
            }
            return values;
        }

        private MusicBoxBlockEntity box() {
            if (level.getBlockEntity(position) instanceof MusicBoxBlockEntity box) return box;
            throw new IllegalStateException("P4 八音盒夹具方块实体丢失");
        }

        private void teleportAliceToMusicBox() {
            alice.teleportTo(level, position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D, 0.0F, 90.0F);
        }

        private String oggUrl() { return baseUrl + "blindbox-ci-tone.ogg"; }
        private String mp3Url() { return baseUrl + "blindbox-ci-tone.mp3"; }
        private String brokenUrl() { return baseUrl + "blindbox-ci-broken.ogg"; }
        private String positionString() { return position.getX() + "," + position.getY() + "," + position.getZ(); }

        private void cleanup() {
            alice.closeContainer();
            level.setBlock(position, previousState, 3);
            alice.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand.copy());
            alice.teleportTo(level, previousAlicePosition.getX() + 0.5D, previousAlicePosition.getY(), previousAlicePosition.getZ() + 0.5D,
                    previousYaw, previousPitch);
            alice.containerMenu.broadcastChanges();
        }

        private void fail(Exception exception) {
            if (phase == Phase.FAILED) return;
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P4_MUSIC=failed", exception);
        }

        private static ServerPlayer requiredPlayer(MinecraftServer server, String name) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) throw new IllegalStateException("P4 八音盒场景缺少在线玩家：" + name);
            return player;
        }

        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_P4_MARKER_DIR");
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_P4_MARKER_DIR");
            Path directory = Path.of(configured).toAbsolutePath();
            if (!Files.isDirectory(directory)) throw new IllegalStateException("P4 八音盒 marker 目录不存在");
            return directory;
        }

        private static String audioBaseUrl() {
            String configured = System.getenv("BLINDBOX_CITEST_P4_AUDIO_BASE_URL");
            if (configured == null || configured.isBlank() || !configured.startsWith("https://")) {
                throw new IllegalStateException("缺少安全的 BLINDBOX_CITEST_P4_AUDIO_BASE_URL");
            }
            return configured.endsWith("/") ? configured : configured + "/";
        }
    }
}
