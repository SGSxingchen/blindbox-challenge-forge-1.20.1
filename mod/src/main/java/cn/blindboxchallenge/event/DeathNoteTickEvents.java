package cn.blindboxchallenge.event;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.service.DeathNoteService;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 独立服务器 tick 入口，客户端绝不参与排程或伤害判定。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID)
public final class DeathNoteTickEvents {
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) DeathNoteService.tick(event.getServer());
    }

    private DeathNoteTickEvents() {}
}
