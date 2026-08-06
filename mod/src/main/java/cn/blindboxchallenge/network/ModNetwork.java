package cn.blindboxchallenge.network;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;

public final class ModNetwork {
    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation(BlindBoxChallenge.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
    private static int nextId;

    public static void register() {
        CHANNEL.registerMessage(nextId++, CommitPackingPacket.class, CommitPackingPacket::encode, CommitPackingPacket::decode, CommitPackingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, RequestDoubleJumpPacket.class, RequestDoubleJumpPacket::encode, RequestDoubleJumpPacket::decode,
                RequestDoubleJumpPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, SyncPlayerAbilityPacket.class, SyncPlayerAbilityPacket::encode, SyncPlayerAbilityPacket::decode,
                SyncPlayerAbilityPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, CommitLetterEditPacket.class, CommitLetterEditPacket::encode, CommitLetterEditPacket::decode,
                CommitLetterEditPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, CommitDeathNotePacket.class, CommitDeathNotePacket::encode, CommitDeathNotePacket::decode,
                CommitDeathNotePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, ShowLetterPacket.class, ShowLetterPacket::encode, ShowLetterPacket::decode,
                ShowLetterPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, CommitMusicBoxUrlPacket.class, CommitMusicBoxUrlPacket::encode, CommitMusicBoxUrlPacket::decode,
                CommitMusicBoxUrlPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, PlayMusicBoxPacket.class, PlayMusicBoxPacket::encode, PlayMusicBoxPacket::decode,
                PlayMusicBoxPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private ModNetwork() {}
}
