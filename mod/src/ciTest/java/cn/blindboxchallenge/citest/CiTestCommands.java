package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
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
                .then(Commands.literal("export").executes(context -> export(context.getSource())))
                .then(Commands.literal("seed_recovery_fixture").executes(context -> seedRecoveryFixture(context.getSource()))));
    }

    /**
     * 写入带唯一名称、耐久、附魔和自定义 NBT 的 PACK/OPEN 持久事务夹具。
     * 夹具只存在于独立 ciTest Jar，用于验证 save-all flush 后强杀恢复时
     * bundle、reservation、payload 与 receipts 不会变化或重复。
     */
    private static int seedRecoveryFixture(CommandSourceStack source) {
        try {
            BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(source.getServer().overworld());
            if (!data.transactions().isEmpty() || !data.bundles().isEmpty() || !data.openReservations().isEmpty()) {
                source.sendFailure(Component.literal("CI 恢复夹具要求空奖池和空事务日志"));
                return 0;
            }

            long gameTime = source.getServer().overworld().getGameTime();
            UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

            ItemStack packStack = uniqueStack("citest-pack-asset", 1, 7);
            PrizeBundle packBundle = new PrizeBundle(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"), playerId, gameTime, 1001L, List.of(packStack));
            TransactionRecord pack = TransactionRecord.createV2(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"), playerId,
                    UUID.fromString("44444444-4444-4444-4444-444444444444"), packBundle.id(),
                    TransactionRecord.Kind.PACK, packBundle, gameTime,
                    receipts("pack", packStack), "citest-pack-before", "citest-pack-after")
                    .withStage(TransactionRecord.Stage.PLAYER_APPLIED, gameTime);
            data.prepare(pack);
            data.ensureBundle(packBundle);

            ItemStack openStack = uniqueStack("citest-open-asset", 1, 11);
            PrizeBundle openBundle = new PrizeBundle(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"), playerId, gameTime, 1002L, List.of(openStack));
            UUID openTransactionId = UUID.fromString("66666666-6666-6666-6666-666666666666");
            TransactionRecord open = TransactionRecord.createV2(
                    openTransactionId, playerId,
                    UUID.fromString("77777777-7777-7777-7777-777777777777"), openBundle.id(),
                    TransactionRecord.Kind.OPEN, openBundle, gameTime,
                    receipts("open", openStack), "citest-open-before", "citest-open-after");
            data.ensureBundle(openBundle);
            data.prepare(open);
            if (!data.reserveOpen(openBundle.id(), openTransactionId)) {
                throw new IllegalStateException("cannot reserve OPEN recovery fixture");
            }
            data.markStage(openTransactionId, TransactionRecord.Stage.PLAYER_APPLIED, gameTime);

            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_FIXTURE=seeded"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 恢复夹具写入失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot seed recovery fixture", exception);
            return 0;
        }
    }

    private static ItemStack uniqueStack(String marker, int count, int damage) {
        ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE, count);
        stack.setDamageValue(damage);
        stack.setHoverName(Component.literal(marker));
        stack.enchant(Enchantments.UNBREAKING, 2);
        stack.getOrCreateTag().putString("blindbox_citest_marker", marker);
        stack.getOrCreateTag().putUUID("blindbox_citest_uuid", UUID.nameUUIDFromBytes(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return stack;
    }

    private static CompoundTag receipts(String kind, ItemStack stack) {
        CompoundTag receipts = new CompoundTag();
        receipts.putString("citest_kind", kind);
        receipts.put("citest_unique_stack", stack.save(new CompoundTag()));
        receipts.putBoolean("citest_persist_across_sigkill", true);
        return receipts;
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
