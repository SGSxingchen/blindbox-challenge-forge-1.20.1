package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.PlayerAbilitySyncEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 客户端只读能力快照，供按键层决定是否发送意图；服务端始终重新校验。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, value = Dist.CLIENT)
public final class ClientPlayerAbilityState {
    private static final Map<Integer, Boolean> LEARNED_YIJIN = new ConcurrentHashMap<>();

    public static void apply(int entityId, boolean learned) { LEARNED_YIJIN.put(entityId, learned); }
    public static boolean hasLearnedYiJin(int entityId) { return LEARNED_YIJIN.getOrDefault(entityId, false); }
    public static void clear() { LEARNED_YIJIN.clear(); }

    @SubscribeEvent
    public static void sync(PlayerAbilitySyncEvent event) { apply(event.entityId(), event.learnedYiJin()); }

    private ClientPlayerAbilityState() {}
}
