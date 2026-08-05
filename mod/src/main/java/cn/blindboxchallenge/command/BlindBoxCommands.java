package cn.blindboxchallenge.command;

import com.mojang.brigadier.CommandDispatcher;
import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.IntegerArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 仅管理员调试；正常玩法不调用本命令。 */
public final class BlindBoxCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blindbox").requires(source -> source.hasPermission(2))
                .then(Commands.literal("pool")
                        .then(Commands.literal("count").executes(context -> {
                            int count = BlindBoxPoolSavedData.get(context.getSource().getLevel()).bundleCount();
                            context.getSource().sendSuccess(() -> Component.literal("盲盒奖池条目数：" + count), false);
                            return count;
                        }))
                        .then(Commands.literal("clear").executes(context -> {
                            BlindBoxPoolSavedData.get(context.getSource().getLevel()).clearForDebug();
                            context.getSource().sendSuccess(() -> Component.literal("已清空测试奖池。"), true);
                            return 1;
                        }))
                        .then(Commands.literal("inject").then(Commands.argument("item", ItemArgument.item()).then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> inject(context.getSource(), ItemArgument.getItem(context, "item").createItemStack(IntegerArgumentType.getInteger(context, "count"), false)))))));
    }

    private static int inject(CommandSourceStack source, ItemStack stack) {
        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(source.getLevel());
        PrizeBundle bundle = data.createBundle(source.getEntity() == null ? new java.util.UUID(0L, 0L) : source.getEntity().getUUID(), source.getLevel().getGameTime(), List.of(stack));
        data.injectForDebug(bundle);
        source.sendSuccess(() -> Component.literal("已注入确定性测试奖项：" + stack.getHoverName().getString()), true);
        return 1;
    }
    private BlindBoxCommands() {}
}
