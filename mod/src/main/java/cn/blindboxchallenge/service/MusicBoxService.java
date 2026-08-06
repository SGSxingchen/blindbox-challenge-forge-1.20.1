package cn.blindboxchallenge.service;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.menu.MusicBoxMenu;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.network.PlayMusicBoxPacket;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

/** 服务端只保存 URL 与广播一次事件，永不下载、缓存、解码或转发音频字节。 */
public final class MusicBoxService {
    private MusicBoxService() {}

    public static void openEditor(ServerPlayer player, MusicBoxBlockEntity box) {
        if (!mayEdit(player, box)) return;
        UUID session = UUID.randomUUID();
        MenuProvider provider = new SimpleMenuProvider((containerId, inventory, ignored) -> new MusicBoxMenu(containerId, inventory,
                session, box.getBlockPos(), box.instanceId(), box.revision(), box.url()), Component.translatable("menu.blindboxchallenge.music_box"));
        NetworkHooks.openScreen(player, provider, buffer -> {
            buffer.writeUUID(session);
            buffer.writeBlockPos(box.getBlockPos());
            buffer.writeUUID(box.instanceId());
            buffer.writeVarInt(box.revision());
            buffer.writeUtf(box.url(), AudioUrlPolicy.MAX_URL_LENGTH);
        });
    }

    public static void play(ServerPlayer player, MusicBoxBlockEntity box) {
        if (!mayEdit(player, box) || !box.configured()) return;
        final String normalized;
        try {
            normalized = AudioUrlPolicy.normalizeHttpsUrl(box.url());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        ModNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new PlayMusicBoxPacket(eventId, normalized, box.getBlockPos(),
                box.getLevel().getGameTime()));
    }

    /** 同时遵守玩家建造能力与该维度的实际交互权限（出生点保护等）。 */
    private static boolean mayEdit(ServerPlayer player, MusicBoxBlockEntity box) {
        return player.mayBuild() && box.getLevel() != null && box.getLevel().mayInteract(player, box.getBlockPos());
    }
}
