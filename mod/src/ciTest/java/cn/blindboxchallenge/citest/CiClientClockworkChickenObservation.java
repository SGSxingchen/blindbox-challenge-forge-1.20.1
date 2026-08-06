package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.entity.ClockworkChickenEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅 ciTest 客户端实际观察同步到本地世界的小黄鸡 UUID 与 Fuse，绝不由脚本预写。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientClockworkChickenObservation {
    private static boolean written;

    private CiClientClockworkChickenObservation() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || written) return;
        String configured = System.getProperty("blindbox.ci.chickenMarker");
        if (configured == null || configured.isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) return;
        AABB viewingArea = minecraft.player.getBoundingBox().inflate(96.0D);
        for (ClockworkChickenEntity chicken : minecraft.level.getEntitiesOfClass(ClockworkChickenEntity.class, viewingArea)) {
            int fuse = chicken.fuse();
            if (fuse <= 0) continue;
            writeMarker(Path.of(configured).toAbsolutePath(), chicken.stableEntityId(), minecraft.player.getUUID(), fuse);
            written = true;
            return;
        }
    }

    private static void writeMarker(Path marker, java.util.UUID chicken, java.util.UUID observer, int fuse) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("小黄鸡 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\n"
                    + "observer_uuid=" + observer + "\n"
                    + "chicken=" + chicken + "\n"
                    + "fuse=" + fuse + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入真实小黄鸡观察 marker", exception);
        }
    }
}
