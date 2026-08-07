package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.registry.ModBlocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * P5 只在 ciTest Jar 中的八音盒缓存压力协调器。服务端只摆放生产方块、读取生产 BE 和回读两个
 * 客户端在 SoundEngine 真实读取 PCM 后的 marker；从不下载、解码、调用播放服务或构造 S2C 包。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P5MusicCacheCiScenario {
    public static final BlockPos MUSIC_BOX_OFFSET = new BlockPos(40, 160, 32);
    public static final int PRESSURE_ROUNDS = 5;
    private static ActiveScenario active;

    private P5MusicCacheCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P5 八音盒缓存压力场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P5_MUSIC_CACHE_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P5 music cache scenario", exception);
            source.sendFailure(Component.literal("CI P5 八音盒缓存压力启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P5 八音盒缓存压力场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P5_MUSIC_CACHE_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P5 music cache scenario", exception);
            source.sendFailure(Component.literal("CI P5 八音盒缓存压力清理失败：" + exception.getClass().getSimpleName()));
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

    private enum Phase {
        WAIT_ENABLED, WAIT_FILL_CONFIGURATION, WAIT_FILL_PCM, WAIT_EVICTION_CONFIGURATION, WAIT_EVICTION_PCM,
        WAIT_SINGLE_FLIGHT_CONFIGURATION, WAIT_SINGLE_FLIGHT_PCM, WAIT_CORRUPTION, WAIT_CORRUPT_CONFIGURATION,
        WAIT_CORRUPT_PCM, READY, FAILED
    }

    private static final class ActiveScenario {
        /** 所有由 P5 服务端读取的客户端事实字段；未知字段不能静默混入验收输入。 */
        private static final Set<String> MARKER_FIELDS = Set.of("schema", "observer_uuid", "event_uuid", "url", "kind", "cache_hit",
                "single_flight_follower", "source", "pcm_bytes", "s2c_observed", "original_bytes", "truncated_bytes", "corruption_injected");
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
        private final Set<UUID> consumedEvents = new HashSet<>();
        private Phase phase = Phase.WAIT_ENABLED;
        private int fillRound = 1;

        private ActiveScenario(ServerLevel level, ServerPlayer alice, ServerPlayer bob, BlockPos position, BlockState previousState,
                               Path markerDirectory, String baseUrl) {
            this.level = level;
            this.alice = alice;
            aliceId = alice.getUUID();
            bobId = bob.getUUID();
            this.position = position;
            this.previousState = previousState;
            previousMainHand = alice.getMainHandItem().copy();
            previousAlicePosition = alice.blockPosition();
            previousYaw = alice.getYRot();
            previousPitch = alice.getXRot();
            this.markerDirectory = markerDirectory;
            this.baseUrl = baseUrl;
            startedAt = level.getGameTime();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            ServerPlayer alice = requiredPlayer(server, "BlindBoxAlice");
            ServerPlayer bob = requiredPlayer(server, "BlindBoxBob");
            ServerLevel level = server.overworld();
            BlockPos position = level.getSharedSpawnPos().offset(MUSIC_BOX_OFFSET);
            if (!level.getBlockState(position).isAir()) throw new IllegalStateException("P5 八音盒缓存压力夹具位置不是空气");
            Path directory = markerDirectory();
            try (var entries = Files.list(directory)) {
                if (entries.anyMatch(path -> path.getFileName().toString().contains("p5-music-cache-"))) {
                    throw new IllegalStateException("P5 八音盒缓存 marker 已存在，拒绝复用旧结果");
                }
            }
            ActiveScenario scenario = new ActiveScenario(level, alice, bob, position, level.getBlockState(position), directory, audioBaseUrl());
            level.setBlock(position, ModBlocks.MUSIC_BOX.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(position) instanceof MusicBoxBlockEntity)) {
                throw new IllegalStateException("P5 八音盒缓存压力方块实体未创建");
            }
            alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            alice.containerMenu.broadcastChanges();
            scenario.teleportAlice();
            return scenario;
        }

        private void tick() throws IOException {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            if (level.getGameTime() - startedAt > 12000L) {
                throw new IllegalStateException("P5 八音盒缓存压力场景超时：" + phase + ", fill_round=" + fillRound
                        + ", diagnostics=" + diagnostics());
            }
            MusicBoxBlockEntity box = box();
            switch (phase) {
                case WAIT_ENABLED -> {
                    if (stage("p5-music-cache-enabled.flag")) phase = Phase.WAIT_FILL_CONFIGURATION;
                }
                case WAIT_FILL_CONFIGURATION -> {
                    if (fillUrl(fillRound).equals(box.url()) && box.revision() == fillRound) phase = Phase.WAIT_FILL_PCM;
                }
                case WAIT_FILL_PCM -> {
                    UUID event = oneSharedRead(fillUrl(fillRound), false);
                    if (event != null) {
                        consumedEvents.add(event);
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_MUSIC_CACHE_FILL_{}=success", fillRound);
                        if (fillRound++ == PRESSURE_ROUNDS) phase = Phase.WAIT_EVICTION_CONFIGURATION;
                        else phase = Phase.WAIT_FILL_CONFIGURATION;
                    }
                }
                case WAIT_EVICTION_CONFIGURATION -> {
                    if (stage("p5-music-cache-eviction-reload.flag") && fillUrl(1).equals(box.url())
                            && box.revision() == PRESSURE_ROUNDS + 1) phase = Phase.WAIT_EVICTION_PCM;
                }
                case WAIT_EVICTION_PCM -> {
                    UUID event = oneSharedRead(fillUrl(1), false);
                    if (event != null) {
                        consumedEvents.add(event);
                        phase = Phase.WAIT_SINGLE_FLIGHT_CONFIGURATION;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_MUSIC_CACHE_EVICTION_REDOWNLOAD=success");
                    }
                }
                case WAIT_SINGLE_FLIGHT_CONFIGURATION -> {
                    if (stage("p5-music-cache-singleflight.flag") && singleFlightUrl().equals(box.url())
                            && box.revision() == PRESSURE_ROUNDS + 2) phase = Phase.WAIT_SINGLE_FLIGHT_PCM;
                }
                case WAIT_SINGLE_FLIGHT_PCM -> {
                    if (twoSharedSingleFlightReads()) {
                        phase = Phase.WAIT_CORRUPTION;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_MUSIC_CACHE_SINGLE_FLIGHT=success");
                    }
                }
                case WAIT_CORRUPTION -> {
                    if (corruptionInjected(aliceId, "client-1") && corruptionInjected(bobId, "client-2")) {
                        phase = Phase.WAIT_CORRUPT_CONFIGURATION;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_MUSIC_CACHE_CORRUPTION=ready");
                    }
                }
                case WAIT_CORRUPT_CONFIGURATION -> {
                    if (stage("p5-music-cache-corrupt-retry.flag") && fillUrl(PRESSURE_ROUNDS).equals(box.url())
                            && box.revision() == PRESSURE_ROUNDS + 3) phase = Phase.WAIT_CORRUPT_PCM;
                }
                case WAIT_CORRUPT_PCM -> {
                    UUID event = oneSharedRead(fillUrl(PRESSURE_ROUNDS), false);
                    if (event != null) {
                        consumedEvents.add(event);
                        phase = Phase.READY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_MUSIC_CACHE_CLIENTS=success");
                    }
                }
                default -> { }
            }
        }

        private UUID oneSharedRead(String url, boolean cacheHit) throws IOException {
            Map<UUID, Map<String, String>> aliceReads = matchingReads("client-1", aliceId, url, cacheHit);
            Map<UUID, Map<String, String>> bobReads = matchingReads("client-2", bobId, url, cacheHit);
            List<UUID> shared = new ArrayList<>();
            for (UUID event : aliceReads.keySet()) if (bobReads.containsKey(event) && !consumedEvents.contains(event)) shared.add(event);
            if (shared.size() > 1) throw new IllegalStateException("P5 八音盒缓存压力当前轮收到多个非单飞 PCM 事件：" + url);
            return shared.isEmpty() ? null : shared.get(0);
        }

        private boolean twoSharedSingleFlightReads() throws IOException {
            Map<UUID, Map<String, String>> aliceReads = matchingReads("client-1", aliceId, singleFlightUrl(), false);
            Map<UUID, Map<String, String>> bobReads = matchingReads("client-2", bobId, singleFlightUrl(), false);
            List<UUID> shared = new ArrayList<>();
            for (UUID event : aliceReads.keySet()) if (bobReads.containsKey(event) && !consumedEvents.contains(event)) shared.add(event);
            if (shared.size() < 2) return false;
            if (shared.size() > 2) throw new IllegalStateException("P5 同 URL 单飞收到多于两次真实播放事件");
            for (String client : List.of("client-1", "client-2")) {
                int owners = 0;
                int followers = 0;
                Map<UUID, Map<String, String>> reads = client.equals("client-1") ? aliceReads : bobReads;
                for (UUID event : shared) {
                    String joined = reads.get(event).get("single_flight_follower");
                    if ("true".equals(joined)) followers++;
                    else if ("false".equals(joined)) owners++;
                    else throw new IllegalStateException(client + " P5 单飞 marker 缺少明确 follower 事实");
                }
                if (owners != 1 || followers != 1) {
                    throw new IllegalStateException(client + " P5 同 URL 未形成恰一 owner 与恰一 follower");
                }
            }
            consumedEvents.addAll(shared);
            return true;
        }

        private Map<UUID, Map<String, String>> matchingReads(String client, UUID observer, String url, boolean cacheHit) throws IOException {
            Map<UUID, Map<String, String>> matches = new HashMap<>();
            for (Map<String, String> marker : readMarkers(client + "-p5-music-cache-read-")) {
                if (!"1".equals(marker.get("schema")) || !observer.toString().equals(marker.get("observer_uuid"))
                        || !url.equals(marker.get("url")) || !"OGG".equals(marker.get("kind"))
                        || !Boolean.toString(cacheHit).equals(marker.get("cache_hit"))
                        || !"true".equals(marker.get("s2c_observed")) || !positionString().equals(marker.get("source"))) continue;
                UUID event = UUID.fromString(marker.getOrDefault("event_uuid", ""));
                int pcm = Integer.parseInt(marker.getOrDefault("pcm_bytes", "0"));
                if (pcm <= 0 || matches.putIfAbsent(event, marker) != null) {
                    throw new IllegalStateException(client + " P5 八音盒 PCM marker 非法或重复");
                }
            }
            return matches;
        }

        private boolean corruptionInjected(UUID observer, String client) throws IOException {
            Path marker = markerDirectory.resolve(client + "-p5-music-cache-corrupted.marker");
            if (!Files.isRegularFile(marker)) return false;
            Map<String, String> values = parseMarker(marker);
            if (!"1".equals(values.get("schema")) || !observer.toString().equals(values.get("observer_uuid"))
                    || !fillUrl(PRESSURE_ROUNDS).equals(values.get("url")) || !"true".equals(values.get("corruption_injected"))
                    || Long.parseLong(values.getOrDefault("original_bytes", "0")) <= 13L * 1024L * 1024L
                    || Long.parseLong(values.getOrDefault("truncated_bytes", "-1")) != 64L) {
                throw new IllegalStateException(client + " P5 损坏缓存操作 marker 非法");
            }
            return true;
        }

        private List<Map<String, String>> readMarkers(String prefix) throws IOException {
            List<Map<String, String>> result = new ArrayList<>();
            try (var files = Files.list(markerDirectory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith(prefix)
                        && path.getFileName().toString().endsWith(".marker")).toList()) result.add(parseMarker(file));
            }
            return result;
        }

        private String diagnostics() throws IOException {
            try (var files = Files.list(markerDirectory)) {
                long reads = files.filter(path -> path.getFileName().toString().contains("p5-music-cache-read-")).count();
                return "pcm_markers=" + reads + ", box_url=" + box().url() + ", box_revision=" + box().revision();
            }
        }

        private static Map<String, String> parseMarker(Path marker) throws IOException {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                // URL 的 query 允许合法的 key=value；只以第一个等号分隔字段名和值，不能把 URL
                // 自身的等号误判为 marker 结构错误。字段名、空值、未知字段和重复字段仍严格拒绝。
                if (separator <= 0) throw new IllegalStateException("P5 八音盒缓存 marker 格式非法：" + marker);
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!key.matches("[a-z][a-z0-9_]*") || value.isEmpty() || !MARKER_FIELDS.contains(key)) {
                    throw new IllegalStateException("P5 八音盒缓存 marker 字段非法：" + marker);
                }
                if (values.put(key, value) != null) throw new IllegalStateException("P5 八音盒缓存 marker 有重复字段：" + marker);
            }
            return values;
        }

        private boolean stage(String name) { return Files.isRegularFile(markerDirectory.resolve(name)); }
        private MusicBoxBlockEntity box() {
            if (level.getBlockEntity(position) instanceof MusicBoxBlockEntity box) return box;
            throw new IllegalStateException("P5 八音盒缓存压力方块实体丢失");
        }
        private String fillUrl(int round) { return baseUrl + "blindbox-ci-cache-pressure.ogg?ci=p5-fill-" + round; }
        private String singleFlightUrl() { return baseUrl + "blindbox-ci-cache-pressure.ogg?ci=p5-singleflight"; }
        private String positionString() { return position.getX() + "," + position.getY() + "," + position.getZ(); }
        private void teleportAlice() { alice.teleportTo(level, position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D, 0.0F, 90.0F); }
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
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P5_MUSIC_CACHE=failed", exception);
        }
        private static ServerPlayer requiredPlayer(MinecraftServer server, String name) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) throw new IllegalStateException("P5 八音盒缓存压力缺少在线玩家：" + name);
            return player;
        }
        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_P5_MARKER_DIR");
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_P5_MARKER_DIR");
            Path directory = Path.of(configured).toAbsolutePath();
            if (!Files.isDirectory(directory)) throw new IllegalStateException("P5 八音盒缓存 marker 目录不存在");
            return directory;
        }
        private static String audioBaseUrl() {
            String configured = System.getenv("BLINDBOX_CITEST_P5_AUDIO_BASE_URL");
            if (configured == null || configured.isBlank() || !configured.startsWith("https://")) {
                throw new IllegalStateException("缺少安全的 BLINDBOX_CITEST_P5_AUDIO_BASE_URL");
            }
            return configured.endsWith("/") ? configured : configured + "/";
        }
    }
}
