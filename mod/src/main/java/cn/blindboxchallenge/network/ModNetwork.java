package cn.blindboxchallenge.network;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation(BlindBoxChallenge.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
    private static int nextId;

    public static void register() {
        CHANNEL.registerMessage(nextId++, CommitPackingPacket.class, CommitPackingPacket::encode, CommitPackingPacket::decode, CommitPackingPacket::handle);
    }

    private ModNetwork() {}
}
