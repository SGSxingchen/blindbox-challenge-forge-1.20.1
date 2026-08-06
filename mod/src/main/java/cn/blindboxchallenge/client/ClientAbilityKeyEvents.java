package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.network.ModNetwork;
import cn.blindboxchallenge.network.RequestDoubleJumpPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** 二段跳按键只发一次无参数请求，绝不在客户端自行写速度或使用次数。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, value = Dist.CLIENT)
public final class ClientAbilityKeyEvents {
    private static final KeyMapping DOUBLE_JUMP = new KeyMapping("key.blindboxchallenge.double_jump",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, "key.categories.blindboxchallenge");

    private ClientAbilityKeyEvents() {}

    static KeyMapping doubleJumpKey() { return DOUBLE_JUMP; }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !DOUBLE_JUMP.consumeClick()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.onGround() || minecraft.player.isFallFlying()
                || !ClientPlayerAbilityState.hasLearnedYiJin(minecraft.player.getId())) return;
        ModNetwork.CHANNEL.sendToServer(new RequestDoubleJumpPacket());
    }
}
