package cn.blindboxchallenge.citest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 011 的客户端实体观察器。它没有服务端 UUID 输入：仅当本客户端实际跟踪到三个带临时夹具名的
 * 实体，且年龄符合两只成猪和一只幼猪时才写入 marker；服务端会把每个 UUID 与自己由正式书本
 * 入口创建的实体逐项比对。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientPigBreedingObservation {
    private static final double OBSERVATION_RADIUS = 24.0D;
    private static boolean written;

    private CiClientPigBreedingObservation() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || written) return;
        String configuredMarker = System.getProperty("blindbox.ci.pigMarker");
        if (configuredMarker == null || configuredMarker.isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer observer = minecraft.player;
        if (minecraft.level == null || observer == null || minecraft.getConnection() == null) return;

        // 三个名字只标识本 ciTest 场景，并不携带 UUID；客户端必须先从真实实体跟踪包取得名称、
        // 年龄和 UUID。这样也不会误把此前 P3 服务端业务夹具的普通猪写成观察结果。
        AABB viewingArea = observer.getBoundingBox().inflate(OBSERVATION_RADIUS);
        List<Pig> pigs = minecraft.level.getEntitiesOfClass(Pig.class, viewingArea, Pig::isAlive);
        Pig namedFirst = namedPig(pigs, P3PigBreedingCiScenario.PARENT_ONE_FIXTURE_NAME);
        Pig namedSecond = namedPig(pigs, P3PigBreedingCiScenario.PARENT_TWO_FIXTURE_NAME);
        Pig namedChild = namedPig(pigs, P3PigBreedingCiScenario.CHILD_FIXTURE_NAME);
        if (namedFirst == null || namedSecond == null || namedChild == null
                || namedFirst.getAge() < 0 || namedSecond.getAge() < 0 || namedChild.getAge() >= 0) return;

        UUID parentOne = namedFirst.getUUID();
        UUID parentTwo = namedSecond.getUUID();
        if (parentOne.toString().compareTo(parentTwo.toString()) > 0) {
            UUID swap = parentOne;
            parentOne = parentTwo;
            parentTwo = swap;
        }
        UUID child = namedChild.getUUID();
        if (parentOne.equals(parentTwo) || parentOne.equals(child) || parentTwo.equals(child)) return;
        writeMarker(Path.of(configuredMarker).toAbsolutePath(), observer.getUUID(), parentOne, parentTwo, child);
        written = true;
    }

    private static Pig namedPig(List<Pig> pigs, String expectedName) {
        List<Pig> matches = pigs.stream().filter(pig -> pig.hasCustomName()
                && expectedName.equals(pig.getCustomName().getString())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void writeMarker(Path marker, UUID observer, UUID parentOne, UUID parentTwo, UUID child) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("高效养猪 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Files.writeString(marker, "schema=1\n"
                    + "observer_uuid=" + observer + "\n"
                    + "parent_one=" + parentOne + "\n"
                    + "parent_two=" + parentTwo + "\n"
                    + "child=" + child + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入高效养猪真实实体观察 marker：" + marker, exception);
        }
    }
}
