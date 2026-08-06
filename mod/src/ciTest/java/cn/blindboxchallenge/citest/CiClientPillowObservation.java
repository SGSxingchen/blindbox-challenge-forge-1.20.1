package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.entity.PillowProjectileEntity;
import cn.blindboxchallenge.entity.PillowSeatEntity;
import cn.blindboxchallenge.entity.PillowVariant;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 只在 ciTest 客户端 Jar 中运行的 P3 抱枕观察器。
 * 不从脚本预写结果：每个 UUID 都来自客户端当前真实跟踪到的实体；服务端会反向比对两份 marker。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientPillowObservation {
    private static UUID seatId;
    private static UUID stoneProjectileId;
    private static UUID diamondProjectileId;
    private static UUID hitTargetId;
    private static UUID stoneReturnId;
    private static UUID diamondReturnId;
    private static boolean written;

    private CiClientPillowObservation() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || written) return;
        String markerValue = System.getProperty("blindbox.ci.pillowMarker");
        if (markerValue == null || markerValue.isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) return;
        AABB viewingArea = minecraft.player.getBoundingBox().inflate(96.0D);
        observeSeat(minecraft, viewingArea);
        observeProjectiles(minecraft, viewingArea);
        observeReturns(minecraft, viewingArea);
        if (seatId != null && stoneProjectileId != null && diamondProjectileId != null && hitTargetId != null
                && stoneReturnId != null && diamondReturnId != null) {
            writeMarker(Path.of(markerValue).toAbsolutePath());
            written = true;
        }
    }

    private static void observeSeat(Minecraft minecraft, AABB viewingArea) {
        List<PillowSeatEntity> seats = minecraft.level.getEntitiesOfClass(PillowSeatEntity.class, viewingArea);
        if (!seats.isEmpty()) seatId = seats.get(0).getUUID();
    }

    private static void observeProjectiles(Minecraft minecraft, AABB viewingArea) {
        for (PillowProjectileEntity projectile : minecraft.level.getEntitiesOfClass(PillowProjectileEntity.class, viewingArea)) {
            if (projectile.variant() == PillowVariant.STONE) {
                stoneProjectileId = projectile.getUUID();
                // 生产端保留命中后实体数个服务端 tick；只有真实同步到 impacted 与目标 UUID 才记为命中观察。
                if (projectile.impacted() && projectile.hitTargetId().isPresent()
                        && tracksEntity(minecraft, viewingArea, projectile.hitTargetId().get())) {
                    hitTargetId = projectile.hitTargetId().get();
                }
            } else if (projectile.variant() == PillowVariant.DIAMOND) {
                diamondProjectileId = projectile.getUUID();
            }
        }
    }

    private static boolean tracksEntity(Minecraft minecraft, AABB viewingArea, UUID expectedId) {
        return minecraft.level.getEntitiesOfClass(Entity.class, viewingArea,
                entity -> expectedId.equals(entity.getUUID())).size() == 1;
    }

    private static void observeReturns(Minecraft minecraft, AABB viewingArea) {
        for (ItemEntity item : minecraft.level.getEntitiesOfClass(ItemEntity.class, viewingArea)) {
            if (item.getItem().is(ModItems.STONE_PILLOW.get())) stoneReturnId = item.getUUID();
            if (item.getItem().is(ModItems.DIAMOND_PILLOW.get())) diamondReturnId = item.getUUID();
        }
    }

    private static void writeMarker(Path marker) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("P3 抱枕 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\n"
                    + "seat=" + seatId + "\n"
                    + "stone_projectile=" + stoneProjectileId + "\n"
                    + "diamond_projectile=" + diamondProjectileId + "\n"
                    + "hit_target=" + hitTargetId + "\n"
                    + "stone_return=" + stoneReturnId + "\n"
                    + "diamond_return=" + diamondReturnId + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 P3 抱枕真实观察 marker：" + marker, exception);
        }
    }
}
