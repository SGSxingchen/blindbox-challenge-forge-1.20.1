package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.data.DeathNoteSavedData;
import cn.blindboxchallenge.menu.DeathNoteMenu;
import cn.blindboxchallenge.menu.LetterEditMenu;
import cn.blindboxchallenge.network.CommitDeathNotePacket;
import cn.blindboxchallenge.network.CommitLetterEditPacket;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.service.DeathNoteService;
import cn.blindboxchallenge.service.LetterService;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** P4 文本会话的服务端负例断言；只验证生产授权入口，绝不替代真实 GUI 成功链路。 */
public final class P4TextNegativeCiAssertions {
    private P4TextNegativeCiAssertions() {}

    public static int run(CommandSourceStack source) {
        ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
        if (alice == null) {
            source.sendFailure(Component.literal("CI P4 负例缺少 Alice"));
            return 0;
        }
        int selected = alice.getInventory().selected;
        ItemStack original = alice.getMainHandItem().copy();
        AbstractContainerMenu originalMenu = alice.containerMenu;
        int schedulesBefore = DeathNoteSavedData.get(source.getServer().overworld()).entries().size();
        try {
            assertLetterRejections(alice, selected);
            assertDeathNoteRejections(alice, selected);
            if (DeathNoteSavedData.get(source.getServer().overworld()).entries().size() != schedulesBefore) {
                throw new IllegalStateException("拒绝包意外建立了死亡笔记排程");
            }
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_TEXT_NEGATIVE=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI P4 文本负例失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P4 text negative assertions", exception);
            return 0;
        } finally {
            alice.getInventory().selected = selected;
            alice.setItemInHand(InteractionHand.MAIN_HAND, original);
            alice.containerMenu = originalMenu;
            alice.containerMenu.broadcastChanges();
        }
    }

    private static void assertLetterRejections(ServerPlayer alice, int slot) {
        ItemStack letter = new ItemStack(ModItems.LETTER.get());
        alice.setItemInHand(InteractionHand.MAIN_HAND, letter);
        UUID instance = LetterService.ensureInstanceId(letter);
        UUID session = UUID.randomUUID();
        LetterEditMenu menu = new LetterEditMenu(701, alice.getInventory(), session, slot, instance, 0, "",
                ModServerConfig.LETTER_MAX_CODE_POINTS.get(), ModServerConfig.LETTER_MAX_LINES.get());
        alice.containerMenu = menu;
        CommitLetterEditPacket validShape = new CommitLetterEditPacket(701, session, slot, instance, 0, "ok");
        if (!CommitLetterEditPacket.isAuthorized(alice, validShape)) throw new IllegalStateException("合法信件会话形状被拒绝");
        if (CommitLetterEditPacket.isAuthorized(alice, new CommitLetterEditPacket(702, session, slot, instance, 0, "ok"))
                || CommitLetterEditPacket.isAuthorized(alice, new CommitLetterEditPacket(701, session, slot, UUID.randomUUID(), 0, "ok"))
                || CommitLetterEditPacket.isAuthorized(alice, new CommitLetterEditPacket(701, session, slot, instance, 1, "ok"))) {
            throw new IllegalStateException("伪造信件容器、实例或修订被接受");
        }
        if (!menu.consumeSubmission() || CommitLetterEditPacket.isAuthorized(alice, validShape)) {
            throw new IllegalStateException("信件一次性会话未拒绝重放");
        }
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.DEATH_NOTE.get()));
        if (CommitLetterEditPacket.isAuthorized(alice, validShape)) throw new IllegalStateException("信件换手后仍被接受");
        assertBodyRejected("x\u00a7a");
        assertBodyRejected("x\u0000");
        assertBodyRejected("x\n".repeat(ModServerConfig.LETTER_MAX_LINES.get()));
        assertBodyRejected("x".repeat(ModServerConfig.LETTER_MAX_CODE_POINTS.get() + 1));
        ItemStack unsafeStored = new ItemStack(ModItems.LETTER.get());
        unsafeStored.getOrCreateTag().putString(LetterService.LETTER_BODY_KEY, "unsafe\u00a7text");
        assertReadRejected(unsafeStored);
    }

    private static void assertDeathNoteRejections(ServerPlayer alice, int slot) {
        ItemStack note = new ItemStack(ModItems.DEATH_NOTE.get());
        alice.setItemInHand(InteractionHand.MAIN_HAND, note);
        UUID instance = LetterService.ensureInstanceId(note);
        UUID session = UUID.randomUUID();
        DeathNoteMenu menu = new DeathNoteMenu(702, alice.getInventory(), session, slot, instance, 0);
        alice.containerMenu = menu;
        CommitDeathNotePacket validShape = new CommitDeathNotePacket(702, session, slot, instance, 0, "BlindBoxBob");
        if (!CommitDeathNotePacket.isAuthorized(alice, validShape)) throw new IllegalStateException("合法死亡笔记会话形状被拒绝");
        if (CommitDeathNotePacket.isAuthorized(alice, new CommitDeathNotePacket(702, session, slot, instance, 1, "BlindBoxBob"))
                || CommitDeathNotePacket.isAuthorized(alice, new CommitDeathNotePacket(703, session, slot, instance, 0, "BlindBoxBob"))
                || CommitDeathNotePacket.isAuthorized(alice, new CommitDeathNotePacket(702, session, slot, UUID.randomUUID(), 0, "BlindBoxBob"))) {
            throw new IllegalStateException("旧修订或伪造死亡笔记容器被接受");
        }
        if (!menu.consumeSubmission() || CommitDeathNotePacket.isAuthorized(alice, validShape)) {
            throw new IllegalStateException("死亡笔记一次性会话未拒绝重放");
        }
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.LETTER.get()));
        if (CommitDeathNotePacket.isAuthorized(alice, validShape)) throw new IllegalStateException("死亡笔记换手后仍被接受");
        if (DeathNoteService.isValidPlayerName("BlindBoxBob\n") || DeathNoteService.isValidPlayerName("ab")
                || DeathNoteService.isValidPlayerName("this_name_is_too_long")) {
            throw new IllegalStateException("非法或越长的死亡笔记目标名被接受");
        }
        int schedulesBefore = DeathNoteSavedData.get(alice.serverLevel()).entries().size();
        if (DeathNoteService.schedule(alice, "OfflineCiTarget")
                || DeathNoteSavedData.get(alice.serverLevel()).entries().size() != schedulesBefore) {
            throw new IllegalStateException("离线死亡笔记目标意外建立排程");
        }
    }

    private static void assertBodyRejected(String body) {
        try {
            LetterService.normalizeAndValidateBody(body);
            throw new IllegalStateException("越限或控制字符信件正文被接受");
        } catch (IllegalArgumentException expected) {
            // 生产服务已拒绝，继续检查下一条独立负例。
        }
    }

    private static void assertReadRejected(ItemStack stack) {
        try {
            LetterService.safeBodyForRead(stack);
            throw new IllegalStateException("损坏 NBT 信件被下发到客户端");
        } catch (IllegalArgumentException expected) {
            // 读取过滤器生效。
        }
    }
}
