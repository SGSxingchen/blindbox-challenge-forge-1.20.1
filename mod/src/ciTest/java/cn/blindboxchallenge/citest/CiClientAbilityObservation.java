package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.client.ClientPlayerAbilityState;
import cn.blindboxchallenge.event.PlayerAbilitySyncEvent;
import cn.blindboxchallenge.service.PlayerAbilityService;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 只位于 ciTest 客户端 Jar。它从实际收到的 {@link PlayerAbilitySyncEvent} 出发，使用真实
 * {@link KeyMapping} 驱动生产按键处理，且只有观察到服务端回传的上升速度才写 C2S marker。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientAbilityObservation {
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static boolean initialSelfSync;
    private static int initialEntityId = -1;
    private static int learnedSelfSyncCount;
    private static boolean selfSyncMarkerWritten;
    private static boolean keyInjected;
    private static boolean serverVelocityObserved;
    private static boolean keyMarkerWritten;
    private static boolean clientCloneEvent;
    private static boolean learnedAfterClone;
    private static boolean dimensionSyncObserved;
    private static boolean lifecycleMarkerWritten;
    private static boolean observedDisconnectAfterLifecycle;
    private static boolean recoveryLogin;
    private static boolean recoverySyncObserved;
    private static boolean recoveryMarkerWritten;
    private static boolean trackingMarkerWritten;
    private static int pendingTrackedEntityId = -1;
    private static boolean aliceScenarioArmed;
    private static boolean bobScenarioArmed;

    private CiClientAbilityObservation() {
    }

    @SubscribeEvent
    public static void onAbilitySync(PlayerAbilitySyncEvent event) {
        String role = role();
        if (role == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        if (event.entityId() == player.getId()) {
            if (ALICE.equals(role)) {
                if (!event.learnedYiJin()) {
                    resetAliceScenario();
                    aliceScenarioArmed = true;
                    CiTestProbe.LOGGER.info("P3 易筋经客户端收到场景 false 同步，已清空旧观察状态，实体={}", player.getId());
                    return;
                }
                if (!aliceScenarioArmed) return;
                initialSelfSync = true;
                learnedSelfSyncCount++;
                CiTestProbe.LOGGER.info("P3 易筋经客户端收到自身能力同步，次数={}，实体={}", learnedSelfSyncCount, player.getId());
                if (initialEntityId < 0) initialEntityId = player.getId();
                // 平台只能在客户端实际收到本次 true S2C 后由服务端撤去；这个标记只记录
                // 已收到的网络事实，不能代表按键、C2S 或服务器速度成功。
                if (!selfSyncMarkerWritten) {
                    writeSelfSyncMarker(player);
                    selfSyncMarkerWritten = true;
                }
                if (clientCloneEvent && learnedSelfSyncCount >= 2) learnedAfterClone = true;
                if (isNether(minecraft) && learnedSelfSyncCount >= 3) dimensionSyncObserved = true;
                // 断线标志仅由 ClientPlayerNetworkEvent.LoggingOut 写入；因此这里证明的是
                // SIGKILL 后新连接实际抵达的 S2C，而不是沿用客户端缓存。
                if (observedDisconnectAfterLifecycle) recoverySyncObserved = true;
            }
            return;
        }
        if (BOB.equals(role)) {
            if (!event.learnedYiJin()) {
                pendingTrackedEntityId = -1;
                trackingMarkerWritten = false;
                bobScenarioArmed = true;
                CiTestProbe.LOGGER.info("P3 易筋经 Bob 客户端收到场景 false 同步，已清空旧跟踪状态，目标={}", event.entityId());
                return;
            }
            if (!bobScenarioArmed || trackingMarkerWritten) return;
            // 同步包与实体生成包的先后不由测试决定；先记住真实收到的 entityId，下一 tick 再解析。
            pendingTrackedEntityId = event.entityId();
        }
    }

    private static void resetAliceScenario() {
        initialSelfSync = false;
        initialEntityId = -1;
        learnedSelfSyncCount = 0;
        selfSyncMarkerWritten = false;
        keyInjected = false;
        serverVelocityObserved = false;
        keyMarkerWritten = false;
        clientCloneEvent = false;
        learnedAfterClone = false;
        dimensionSyncObserved = false;
        lifecycleMarkerWritten = false;
        observedDisconnectAfterLifecycle = false;
        recoveryLogin = false;
        recoverySyncObserved = false;
        recoveryMarkerWritten = false;
    }

    @SubscribeEvent
    public static void onClientClone(ClientPlayerNetworkEvent.Clone event) {
        if (ALICE.equals(role()) && initialSelfSync && keyInjected) {
            clientCloneEvent = true;
            if (learnedSelfSyncCount >= 2) learnedAfterClone = true;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (ALICE.equals(role()) && initialSelfSync && lifecycleMarkerWritten) {
            observedDisconnectAfterLifecycle = true;
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (ALICE.equals(role()) && observedDisconnectAfterLifecycle) recoveryLogin = true;
    }

    // 必须早于正式 ClientAbilityKeyEvents 的同一 END tick 执行，让生产 consumeClick() 消费这次真实映射点击。
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (BOB.equals(role())) {
            observeTrackedAlice(minecraft, player);
            return;
        }
        if (!ALICE.equals(role())) return;
        if (player == null || minecraft.level == null || minecraft.getConnection() == null) return;

        // 调用 KeyMapping.click 而不是直接发送网络包。下一个（或本）客户端 Tick 只能由生产
        // ClientAbilityKeyEvents.consumeClick() 取走该点击并发送无参数 C2S 请求。
        if (initialSelfSync && !keyInjected && !player.onGround()
                && ClientPlayerAbilityState.hasLearnedYiJin(player.getId())) {
            KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_SPACE));
            keyInjected = true;
            CiTestProbe.LOGGER.info("P3 易筋经客户端已通过真实 KeyMapping 注入空格按键，实体={}", player.getId());
        }
        if (keyInjected && !serverVelocityObserved
                && player.getDeltaMovement().y >= PlayerAbilityService.DOUBLE_JUMP_VELOCITY - 0.02D) {
            serverVelocityObserved = true;
            CiTestProbe.LOGGER.info("P3 易筋经客户端已观察到服务端二段跳速度，实体={}", player.getId());
        }
        if (initialSelfSync && keyInjected && serverVelocityObserved && !keyMarkerWritten) {
            writeKeyMarker(player);
            keyMarkerWritten = true;
        }
        if (clientCloneEvent && !learnedAfterClone && learnedSelfSyncCount >= 2) {
            learnedAfterClone = true;
        }
        if (isNether(minecraft) && !dimensionSyncObserved && clientCloneEvent && learnedSelfSyncCount >= 3) {
            // Clone 与跨维均由生产端发送 S2C。计数只在 PlayerAbilitySyncEvent 中增加，
            // 因而即使网络包先于客户端维度对象切换，也不会把本地推断伪装成同步。
            dimensionSyncObserved = true;
        }
        if (keyMarkerWritten && clientCloneEvent && learnedAfterClone && isNether(minecraft)
                && dimensionSyncObserved && !lifecycleMarkerWritten) {
            writeLifecycleMarker(player);
            lifecycleMarkerWritten = true;
        }
        if (recoveryLogin && recoverySyncObserved && isNether(minecraft) && !recoveryMarkerWritten) {
            writeRecoveryMarker(player);
            recoveryMarkerWritten = true;
        }
    }

    private static boolean isNether(Minecraft minecraft) {
        return minecraft.level != null && "minecraft:the_nether".equals(minecraft.level.dimension().location().toString());
    }

    private static void observeTrackedAlice(Minecraft minecraft, LocalPlayer observer) {
        if (trackingMarkerWritten || pendingTrackedEntityId < 0 || minecraft.level == null || observer == null) return;
        Entity target = minecraft.level.getEntity(pendingTrackedEntityId);
        if (target instanceof Player tracked && "BlindBoxAlice".equals(tracked.getGameProfile().getName())) {
            writeTrackingMarker(observer.getUUID(), tracked.getUUID(), pendingTrackedEntityId);
            trackingMarkerWritten = true;
        }
    }

    private static String role() {
        String value = System.getProperty("blindbox.ci.abilityRole");
        return ALICE.equals(value) || BOB.equals(value) ? value : null;
    }

    private static void writeKeyMarker(LocalPlayer player) {
        write(Path.of(required("blindbox.ci.abilityKeyMarker")).toAbsolutePath(), "schema=1\n"
                + "role=alice\n"
                + "self_uuid=" + player.getUUID() + "\n"
                + "self_entity_id=" + initialEntityId + "\n"
                + "received_self_sync=true\n"
                + "key_injected=true\n"
                + "server_velocity_observed=true\n");
    }

    private static void writeSelfSyncMarker(LocalPlayer player) {
        write(Path.of(required("blindbox.ci.abilitySelfSyncMarker")).toAbsolutePath(), "schema=1\n"
                + "role=alice\n"
                + "self_uuid=" + player.getUUID() + "\n"
                + "self_entity_id=" + initialEntityId + "\n"
                + "received_self_sync=true\n");
    }

    private static void writeTrackingMarker(java.util.UUID selfUuid, java.util.UUID trackedUuid, int trackedEntityId) {
        write(Path.of(required("blindbox.ci.abilityTrackingMarker")).toAbsolutePath(), "schema=1\n"
                + "role=bob\n"
                + "self_uuid=" + selfUuid + "\n"
                + "tracked_uuid=" + trackedUuid + "\n"
                + "tracked_entity_id=" + trackedEntityId + "\n"
                + "received_tracking_sync=true\n");
    }

    private static void writeLifecycleMarker(LocalPlayer player) {
        write(Path.of(required("blindbox.ci.abilityLifecycleMarker")).toAbsolutePath(), "schema=1\n"
                + "role=alice\n"
                + "self_uuid=" + player.getUUID() + "\n"
                + "client_clone_event=true\n"
                + "learned_after_clone=true\n"
                + "dimension=minecraft:the_nether\n"
                + "received_dimension_sync=true\n"
                + "key_result_retained=true\n");
    }

    private static void writeRecoveryMarker(LocalPlayer player) {
        write(Path.of(required("blindbox.ci.abilityRecoveryMarker")).toAbsolutePath(), "schema=1\n"
                + "role=alice\n"
                + "self_uuid=" + player.getUUID() + "\n"
                + "dimension=minecraft:the_nether\n"
                + "reconnected_after_server_kill=true\n"
                + "received_recovery_sync=true\n");
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少 " + property);
        return value;
    }

    private static void write(Path marker, String value) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("能力 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Files.writeString(marker, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入真实能力观察 marker：" + marker, exception);
        }
    }
}
