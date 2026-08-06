package cn.blindboxchallenge.service;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.data.DeathNoteSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 死亡笔记的目标解析、持久排程和到期伤害均由逻辑服务端执行。 */
public final class DeathNoteService {
    private DeathNoteService() {}

    public static boolean schedule(ServerPlayer owner, String requestedName) {
        if (!isValidPlayerName(requestedName)) {
            owner.displayClientMessage(Component.translatable("message.blindboxchallenge.death_note_invalid_target"), true);
            return false;
        }
        MinecraftServer server = owner.serverLevel().getServer();
        ServerPlayer target = server.getPlayerList().getPlayerByName(requestedName);
        if (target == null) {
            owner.displayClientMessage(Component.translatable("message.blindboxchallenge.death_note_target_offline"), true);
            return false;
        }
        long dueTick = server.overworld().getGameTime() + ModServerConfig.DEATH_NOTE_DELAY_TICKS.get();
        DeathNoteSavedData.get(owner.serverLevel()).schedule(owner.getUUID(), target.getUUID(), dueTick);
        owner.displayClientMessage(Component.translatable("message.blindboxchallenge.death_note_scheduled", target.getName()), true);
        return true;
    }

    /** 到期时只重新按提交时固化的 UUID 查在线玩家，绝不根据字符串拼接命令。 */
    public static void tick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        for (DeathNoteSavedData.Entry entry : DeathNoteSavedData.get(server.overworld()).takeDue(gameTime)) {
            ServerPlayer target = server.getPlayerList().getPlayer(entry.target());
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (target == null) {
                if (owner != null) owner.sendSystemMessage(Component.translatable("message.blindboxchallenge.death_note_target_left"));
                continue;
            }
            // 需求是“使在线目标死亡”；使用绕过无敌的原版伤害源，避免自定义图腾把已确认的笔记目标改为存活。
            target.hurt(target.damageSources().outOfWorld(), ModServerConfig.DEATH_NOTE_DAMAGE.get().floatValue());
            if (owner != null) owner.sendSystemMessage(Component.translatable("message.blindboxchallenge.death_note_executed", target.getName()));
        }
    }

    public static boolean isValidPlayerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }
}
