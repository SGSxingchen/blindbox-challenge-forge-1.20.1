package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.menu.MusicBoxMenu;
import cn.blindboxchallenge.network.CommitMusicBoxUrlPacket;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Blocks;

/** 047-B 服务端负例：生产菜单授权形状与 URL 策略均拒绝伪造、重放和危险地址，且不进行 HTTP 下载。 */
public final class MusicBoxCiAssertions {
    private MusicBoxCiAssertions() {}

    public static int run(CommandSourceStack source) {
        ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
        if (alice == null) {
            source.sendFailure(Component.literal("CI 八音盒负例缺少 Alice"));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        BlockPos position = level.getSharedSpawnPos().offset(16, 160, 16);
        if (!level.getBlockState(position).isAir()) {
            source.sendFailure(Component.literal("CI 八音盒夹具区域不是空气"));
            return 0;
        }
        AbstractContainerMenu originalMenu = alice.containerMenu;
        ServerLevel originalLevel = alice.serverLevel();
        var originalPosition = alice.position();
        float originalYaw = alice.getYRot();
        float originalPitch = alice.getXRot();
        try {
            level.setBlock(position, ModBlocks.MUSIC_BOX.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(position) instanceof MusicBoxBlockEntity box)) throw new IllegalStateException("八音盒方块实体未创建");
            alice.teleportTo(level, position.getX() + 0.5D, position.getY() + 1.0D, position.getZ() + 0.5D, 0.0F, 0.0F);
            UUID session = UUID.randomUUID();
            MusicBoxMenu menu = new MusicBoxMenu(751, alice.getInventory(), session, position, box.instanceId(), box.revision(), "");
            alice.containerMenu = menu;
            CommitMusicBoxUrlPacket valid = new CommitMusicBoxUrlPacket(751, session, position, box.instanceId(), box.revision(), "https://example.com/audio.ogg");
            if (!CommitMusicBoxUrlPacket.isAuthorized(alice, valid)) throw new IllegalStateException("合法八音盒会话形状被拒绝");
            if (CommitMusicBoxUrlPacket.isAuthorized(alice, new CommitMusicBoxUrlPacket(752, session, position, box.instanceId(), box.revision(), valid.url()))
                    || CommitMusicBoxUrlPacket.isAuthorized(alice, new CommitMusicBoxUrlPacket(751, session, position.above(), box.instanceId(), box.revision(), valid.url()))
                    || CommitMusicBoxUrlPacket.isAuthorized(alice, new CommitMusicBoxUrlPacket(751, session, position, UUID.randomUUID(), box.revision(), valid.url()))
                    || CommitMusicBoxUrlPacket.isAuthorized(alice, new CommitMusicBoxUrlPacket(751, session, position, box.instanceId(), box.revision() + 1, valid.url()))) {
                throw new IllegalStateException("伪造八音盒容器、位置、实例或修订被接受");
            }
            if (!menu.consumeSubmission() || CommitMusicBoxUrlPacket.isAuthorized(alice, valid)) {
                throw new IllegalStateException("八音盒一次性会话未拒绝重放");
            }
            for (String unsafe : List.of("http://example.com/a.ogg", "file:///tmp/a.ogg", "data:audio/ogg;base64,AA==",
                    "https://user@example.com/a.ogg", "https://example.com:444/a.ogg", "https://localhost/a.ogg",
                    "https://127.0.0.1/a.ogg", "https://[::1]/a.ogg", "https://example.com/a.ogg#fragment")) {
                assertRejected(unsafe);
            }
            String normalized = AudioUrlPolicy.normalizeHttpsUrl("HTTPS://Example.COM/a/../tone.ogg?x=1");
            if (!"https://example.com/tone.ogg?x=1".equals(normalized)) throw new IllegalStateException("安全 URL 未稳定规范化");
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_MUSIC_NEGATIVE=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 八音盒负例失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P4 music-box negative assertions", exception);
            return 0;
        } finally {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            alice.containerMenu = originalMenu;
            alice.teleportTo(originalLevel, originalPosition.x, originalPosition.y, originalPosition.z, originalYaw, originalPitch);
            alice.containerMenu.broadcastChanges();
        }
    }

    private static void assertRejected(String value) {
        try {
            AudioUrlPolicy.normalizeHttpsUrl(value);
            throw new IllegalStateException("危险八音盒 URL 被接受：" + value);
        } catch (IllegalArgumentException expected) {
            // 生产 URL 策略已拒绝，继续下一条独立负例。
        }
    }
}
