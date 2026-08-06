package cn.blindboxchallenge.citest;

import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 固定路径导出命令，避免 CI 命令接收任意文件路径。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID)
public final class CiTestCommands {
    private static final Path OUTPUT = Path.of("citest-results", "canonical-state.json");

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("blindboxcitest")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("export").executes(context -> export(context.getSource()))));
    }

    private static int export(CommandSourceStack source) {
        try {
            String sha = System.getenv().getOrDefault("BLINDBOX_PRODUCT_SHA256", "");
            Path target = CanonicalStateExporter.export(source.getServer(), OUTPUT, sha);
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_EXPORT=" + target.toAbsolutePath()), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 状态导出失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot export canonical CI state", exception);
            return 0;
        }
    }

    private CiTestCommands() {}
}
