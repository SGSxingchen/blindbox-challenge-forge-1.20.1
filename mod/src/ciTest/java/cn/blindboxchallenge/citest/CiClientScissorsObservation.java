package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.entity.ReturningScissorsEntity;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 只在 ciTest 客户端 Jar 内观察 045；所有 UUID 都来自已实际跟踪到的生产实体。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientScissorsObservation {
    private static boolean written;

    private CiClientScissorsObservation() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || written) return;
        String configured = System.getProperty("blindbox.ci.scissorsMarker");
        if (configured == null || configured.isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) return;
        AABB viewingArea = minecraft.player.getBoundingBox().inflate(96.0D);
        for (ReturningScissorsEntity scissors : minecraft.level.getEntitiesOfClass(ReturningScissorsEntity.class, viewingArea)) {
            Entity entity = minecraftEntity(scissors);
            if (!scissors.isReturning() || !scissors.returnOwnerId().isPresent() || !scissors.hitTargetId().isPresent()
                    || !scissors.storedStack().is(ModItems.RETURNING_SCISSORS.get()) || !scissors.storedStack().hasTag()
                    || !"normal-hit-return".equals(scissors.storedStack().getTag().getString("ReturningScissorsCiToken"))
                    || !tracksEntity(minecraft, viewingArea, scissors.hitTargetId().get())) continue;
            writeMarker(Path.of(configured).toAbsolutePath(), entity.getUUID(), scissors.hitTargetId().get(), scissors.returnOwnerId().get());
            written = true;
            return;
        }
    }

    private static boolean tracksEntity(Minecraft minecraft, AABB viewingArea, UUID id) {
        return minecraft.level.getEntitiesOfClass(Entity.class, viewingArea, entity -> id.equals(entity.getUUID())).size() == 1;
    }

    private static void writeMarker(Path marker, UUID scissors, UUID target, UUID owner) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("返航剪刀 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\n"
                    + "scissors=" + scissors + "\n"
                    + "target=" + target + "\n"
                    + "owner=" + owner + "\n"
                    + "returning=true\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入真实返航剪刀观察 marker：" + marker, exception);
        }
    }

    private static Entity minecraftEntity(Entity entity) {
        return entity;
    }
}
