package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.event.ServerLifecycleEvents;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.network.CommitPackingPacket;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.BlindBoxService;
import cn.blindboxchallenge.util.StackFingerprint;
import cn.blindboxchallenge.block.BmlCheerStickBlock;
import cn.blindboxchallenge.item.RestrictedFluidContainerItem;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
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
                .then(Commands.literal("seed_recovery_fixture").executes(context -> seedRecoveryFixture(context.getSource())))
                .then(Commands.literal("run_multi_business").executes(context -> runMultiBusiness(context.getSource())))
                .then(Commands.literal("run_p2_business").executes(context -> runP2Business(context.getSource())))
                .then(Commands.literal("prepare_reconnect").executes(context -> prepareReconnect(context.getSource())))
                .then(Commands.literal("verify_reconnect").executes(context -> verifyReconnect(context.getSource()))));
    }

    private static final UUID RECONNECT_TOKEN = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static int prepareReconnect(CommandSourceStack source) {
        try {
            ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (alice == null) throw new IllegalStateException("Alice not online before reconnect test");
            ItemStack box = BlindBoxService.createBlindBox(RECONNECT_TOKEN);
            alice.getInventory().selected = 1;
            alice.setItemInHand(InteractionHand.MAIN_HAND, box);
            box.getItem().use(alice.serverLevel(), alice, InteractionHand.MAIN_HAND);
            if (!BlindBoxItem.hasActiveUseState(alice, box)) throw new IllegalStateException("reconnect opening state missing");
            alice.containerMenu.broadcastChanges();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_RECONNECT_PREPARED=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 重连准备失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot prepare reconnect suite", exception);
            return 0;
        }
    }

    private static int verifyReconnect(CommandSourceStack source) {
        try {
            ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (alice == null) throw new IllegalStateException("Alice not online after reconnect");
            ItemStack box = findBlindBoxByToken(alice, RECONNECT_TOKEN);
            if (box.isEmpty()) throw new IllegalStateException("reconnect token was consumed or lost");
            if (BlindBoxItem.hasActiveUseState(alice, box) || alice.isUsingItem()) {
                throw new IllegalStateException("opening state survived disconnect/reconnect");
            }
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_RECONNECT=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 重连验证失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot verify reconnect suite", exception);
            return 0;
        }
    }

    /** P2 首批基础物品的服务端语义断言；由两个真实客户端在线的专服执行。 */
    private static int runP2Business(CommandSourceStack source) {
        ServerPlayer player = null;
        ItemStack originalMainHand = ItemStack.EMPTY;
        ItemStack originalOffhand = ItemStack.EMPTY;
        ItemStack originalHead = ItemStack.EMPTY;
        float originalHealth = 0.0F;
        List<net.minecraft.world.effect.MobEffectInstance> originalEffects = List.of();
        try {
            player = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (player == null) throw new IllegalStateException("Alice not online for P2 suite");
            // 此套件在 P1 多人资产守恒断言之后运行，绝不能覆盖主副手中的真实业务产物。
            originalMainHand = player.getMainHandItem().copy();
            originalOffhand = player.getOffhandItem().copy();
            originalHead = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).copy();
            originalHealth = player.getHealth();
            originalEffects = player.getActiveEffects().stream().map(net.minecraft.world.effect.MobEffectInstance::new).toList();

            assertFood(ModItems.TRUFFLE_HAM_CRACKER.get(), 2, 0.1F);
            assertFood(ModItems.SUN_CANDY.get(), 2, 0.1F);
            assertFood(ModItems.POTATO_SNACK.get(), 8, 0.8F);
            assertFood(ModItems.RATION_PACK.get(), 20, 1.0F);
            assertFood(ModItems.WHITE_RABBIT_CANDY.get(), 2, 0.1F);
            assertFood(ModItems.DEEP_SEA_FISH.get(), 2, 0.1F);
            assertFood(ModItems.HAM_SAUSAGE.get(), 4, 0.3F);
            assertFood(ModItems.QUAIL_EGG.get(), 2, 0.2F);
            assertFood(ModItems.GREEN_SOY_MILK.get(), 4, 0.3F);
            assertFood(ModItems.BEEF_BITES.get(), 6, 0.6F);
            assertFood(ModItems.OIL_CHESTNUT.get(), 4, 0.3F);
            assertFood(ModItems.WIND_BLOWN_CAKE.get(), 4, 0.3F);
            assertFood(ModItems.SWEET_SOUR_TURKEY_NOODLES.get(), 8, 0.7F);
            assertFood(ModItems.SESAME_RICE_NOODLES.get(), 8, 0.6F);
            assertFood(ModItems.POTATO_CHIPS.get(), 6, 0.5F);
            assertFood(ModItems.BLACK_TRUFFLE_HAM_CRACKER.get(), 2, 0.1F);
            assertFood(ModItems.MAGIC_CRISPY_NOODLES.get(), 6, 0.5F);

            ItemStack adrenaline = new ItemStack(ModItems.ADRENALINE.get(), 2);
            ModItems.ADRENALINE.get().finishUsingItem(adrenaline, player.serverLevel(), player);
            if (adrenaline.getCount() != 1) throw new IllegalStateException("adrenaline was not consumed exactly once");
            assertEffect(player, net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 1, 590);
            assertEffect(player, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 1, 590);
            assertEffect(player, net.minecraft.world.effect.MobEffects.REGENERATION, 3, 590);
            player.removeAllEffects();

            ItemStack totem = new ItemStack(ModItems.RAT_JERKY_TOTEM.get());
            player.setItemInHand(InteractionHand.OFF_HAND, totem);
            player.setHealth(0.5F);
            LivingDeathEvent death = new LivingDeathEvent(player, player.damageSources().generic());
            ServerLifecycleEvents.death(death);
            if (!death.isCanceled() || !totem.isEmpty() || player.getHealth() != 1.0F) {
                throw new IllegalStateException("rat jerky did not preserve vanilla-totem semantics");
            }
            assertEffect(player, net.minecraft.world.effect.MobEffects.REGENERATION, 1, 890);
            assertEffect(player, net.minecraft.world.effect.MobEffects.ABSORPTION, 1, 90);
            assertEffect(player, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 0, 790);
            player.removeAllEffects();
            player.setHealth(player.getMaxHealth());
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

            ItemStack screwdriver = new ItemStack(ModItems.LONG_SCREWDRIVER.get());
            double reach = ModItems.LONG_SCREWDRIVER.get().getDefaultAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                    .get(net.minecraftforge.common.ForgeMod.ENTITY_REACH.get()).stream().mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
            if (reach != 1.0D || screwdriver.getMaxDamage() != net.minecraft.world.item.Tiers.IRON.getUses()) {
                throw new IllegalStateException("long screwdriver attributes differ from specification");
            }

            ItemStack multiTool = new ItemStack(ModItems.PICKAXE_HOE.get());
            if (!multiTool.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                    || !multiTool.canPerformAction(net.minecraftforge.common.ToolActions.HOE_DIG)
                    || ModItems.PICKAXE_HOE.get().getDestroySpeed(multiTool, net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState()) != net.minecraft.world.item.Tiers.IRON.getSpeed()
                    || !ModItems.PICKAXE_HOE.get().isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState())) {
                throw new IllegalStateException("pickaxe-hoe lacks iron pickaxe/hoe semantics");
            }

            ItemStack lighter = new ItemStack(ModItems.LIGHTER.get());
            if (lighter.getMaxDamage() != 64) throw new IllegalStateException("lighter durability is not vanilla flint-and-steel durability");

            ItemStack kazoo = new ItemStack(ModItems.KAZOO.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, kazoo);
            net.minecraft.world.InteractionResultHolder<ItemStack> kazooResult = ModItems.KAZOO.get().use(player.serverLevel(), player, InteractionHand.MAIN_HAND);
            if (!kazooResult.getResult().consumesAction() || !player.getCooldowns().isOnCooldown(ModItems.KAZOO.get())
                    || kazoo.getCount() != 1 || kazoo.getMaxStackSize() != 1) {
                throw new IllegalStateException("kazoo lacks server-authoritative sound/cooldown semantics");
            }
            if (ModItems.KAZOO.get().use(player.serverLevel(), player, InteractionHand.MAIN_HAND).getResult() != net.minecraft.world.InteractionResult.FAIL) {
                throw new IllegalStateException("kazoo cooldown did not reject repeated use");
            }
            player.getCooldowns().removeCooldown(ModItems.KAZOO.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            ItemStack fairyWand = new ItemStack(ModItems.FAIRY_WAND.get());
            double fairyDamage = attributeTotal(ModItems.FAIRY_WAND.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double fairySpeed = attributeTotal(ModItems.FAIRY_WAND.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            double fairyKnockback = attributeTotal(ModItems.FAIRY_WAND.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK);
            if (fairyWand.getMaxDamage() != net.minecraft.world.item.Tiers.WOOD.getUses()
                    || !approximately(fairyDamage, 3.0D)
                    || !approximately(fairySpeed, -2.4D)
                    || !approximately(fairyKnockback, 2.0D)) {
                throw new IllegalStateException("fairy wand lacks wooden-sword and knockback-II semantics: damage="
                        + fairyDamage + ", speed=" + fairySpeed + ", knockback=" + fairyKnockback);
            }

            ItemStack sharkDagger = new ItemStack(ModItems.SHARK_DAGGER_PILLOW.get());
            double sharkDamage = attributeTotal(ModItems.SHARK_DAGGER_PILLOW.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double sharkSpeed = attributeTotal(ModItems.SHARK_DAGGER_PILLOW.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            if (sharkDagger.getMaxDamage() != net.minecraft.world.item.Tiers.STONE.getUses()
                    || !approximately(sharkDamage, 4.0D)
                    || !approximately(sharkSpeed, -2.4D)) {
                throw new IllegalStateException("shark dagger pillow differs from vanilla stone-sword semantics: damage="
                        + sharkDamage + ", speed=" + sharkSpeed);
            }

            assertNailArt();

            ItemStack wings = new ItemStack(ModItems.PINK_BUTTERFLY_WINGS.get());
            if (!(wings.getItem() instanceof net.minecraft.world.item.ElytraItem elytra)
                    || elytra.getEquipmentSlot() != net.minecraft.world.entity.EquipmentSlot.CHEST
                    || wings.getMaxDamage() != 432
                    || !net.minecraft.world.item.ElytraItem.isFlyEnabled(wings)) {
                throw new IllegalStateException("pink butterfly wings lack vanilla elytra chest-slot semantics");
            }
            wings.setDamageValue(wings.getMaxDamage() - 1);
            if (net.minecraft.world.item.ElytraItem.isFlyEnabled(wings)) {
                throw new IllegalStateException("pink butterfly wings remain flyable at vanilla broken threshold");
            }

            ItemStack toyKnife = new ItemStack(ModItems.TOY_KNIFE.get());
            double knifeDamage = attributeTotal(ModItems.TOY_KNIFE.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double knifeSpeed = attributeTotal(ModItems.TOY_KNIFE.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            if (toyKnife.getMaxDamage() != net.minecraft.world.item.Tiers.WOOD.getUses()
                    || !approximately(knifeDamage, 1.0D) || !approximately(knifeSpeed, -2.4D)) {
                throw new IllegalStateException("toy knife is not the conservative low-damage wooden melee profile");
            }

            ItemStack chainsaw = new ItemStack(ModItems.CHAINSAW_SWORD.get());
            double chainsawDamage = attributeTotal(ModItems.CHAINSAW_SWORD.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double chainsawSpeed = attributeTotal(ModItems.CHAINSAW_SWORD.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            double chainsawReach = attributeTotal(ModItems.CHAINSAW_SWORD.get(), net.minecraftforge.common.ForgeMod.ENTITY_REACH.get());
            double stoneAxeDamage = attributeTotal(Items.STONE_AXE, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double stoneAxeSpeed = attributeTotal(Items.STONE_AXE, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            if (!(chainsaw.getItem() instanceof net.minecraft.world.item.AxeItem)
                    || chainsaw.getMaxDamage() != net.minecraft.world.item.Tiers.STONE.getUses()
                    || !approximately(chainsawDamage, stoneAxeDamage) || !approximately(chainsawSpeed, stoneAxeSpeed)
                    || !approximately(chainsawReach, 2.0D)) {
                throw new IllegalStateException("chainsaw sword differs from stone-axe and +2 entity-reach semantics");
            }

            ItemStack eyeMask = new ItemStack(ModItems.EGGY_EYE_MASK.get());
            assertHeadwear(eyeMask, "eggy eye mask");
            EggyEyeMaskItem.onEquipped(player);
            net.minecraft.world.effect.MobEffectInstance eyeMaskBlindness = player.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
            if (eyeMaskBlindness == null || !eyeMaskBlindness.isInfiniteDuration()) {
                throw new IllegalStateException("eggy eye mask did not write server-authoritative blindness");
            }
            EggyEyeMaskItem.onUnequipped(player);
            if (player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
                throw new IllegalStateException("eggy eye mask blindness survived removing the headwear");
            }

            assertHeadwear(new ItemStack(ModItems.FACE_MASK.get()), "face mask");
            ItemStack catDoll = new ItemStack(ModItems.CAT_DOLL.get());
            if (catDoll.getMaxStackSize() != 64
                    || !ModItems.CAT_DOLL.get().getDefaultAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty()) {
                throw new IllegalStateException("cat doll must remain a stackable no-effect collectible");
            }

            ItemStack standee = new ItemStack(ModItems.WENXU_STANDEE.get());
            player.setItemInHand(InteractionHand.OFF_HAND, standee);
            player.setHealth(0.5F);
            LivingDeathEvent standeeDeath = new LivingDeathEvent(player, player.damageSources().generic());
            ServerLifecycleEvents.death(standeeDeath);
            if (!standeeDeath.isCanceled() || !standee.isEmpty() || player.getHealth() != 1.0F) {
                throw new IllegalStateException("wenxu standee did not preserve vanilla-totem semantics");
            }
            assertEffect(player, net.minecraft.world.effect.MobEffects.REGENERATION, 1, 890);
            assertEffect(player, net.minecraft.world.effect.MobEffects.ABSORPTION, 1, 90);
            assertEffect(player, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 0, 790);
            player.removeAllEffects();
            player.setHealth(player.getMaxHealth());
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

            assertContainersAndLighting(player);

            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P2_BUSINESS=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI P2 业务失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P2 business suite", exception);
            return 0;
        } finally {
            if (player != null) {
                player.setItemInHand(InteractionHand.MAIN_HAND, originalMainHand);
                player.setItemInHand(InteractionHand.OFF_HAND, originalOffhand);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, originalHead);
                player.removeAllEffects();
                for (net.minecraft.world.effect.MobEffectInstance effect : originalEffects) player.addEffect(effect);
                player.setHealth(Math.min(originalHealth, player.getMaxHealth()));
                player.containerMenu.broadcastChanges();
            }
        }
    }

    private static double attributeTotal(net.minecraft.world.item.Item item, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return attributeTotal(item, net.minecraft.world.entity.EquipmentSlot.MAINHAND, attribute);
    }

    private static double attributeTotal(net.minecraft.world.item.Item item, net.minecraft.world.entity.EquipmentSlot slot,
                                         net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return item.getDefaultAttributeModifiers(slot)
                .get(attribute).stream().mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
    }

    /** 在两个真实客户端在线的专服中，直接走生产右键入口验证容器和方块状态。 */
    private static void assertContainersAndLighting(ServerPlayer player) {
        if (!(ModItems.GLOW_STICK.get() instanceof net.minecraft.world.item.StandingAndWallBlockItem)
                || !(ModItems.BML_CHEER_STICK.get() instanceof net.minecraft.world.item.StandingAndWallBlockItem)
                || !(ModBlocks.GLOW_STICK.get() instanceof net.minecraft.world.level.block.TorchBlock)
                || !(ModBlocks.BML_CHEER_STICK.get() instanceof net.minecraft.world.level.block.TorchBlock)) {
            throw new IllegalStateException("lighting items are not registered as vanilla-torch block items");
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        net.minecraft.core.BlockPos fluidPos = player.blockPosition().offset(0, 3, -4);
        net.minecraft.core.BlockPos pourPos = fluidPos.north();
        net.minecraft.core.BlockPos cheerPos = fluidPos.east(2);
        net.minecraft.core.BlockPos cheerSupport = cheerPos.below();
        net.minecraft.world.level.block.state.BlockState oldFluid = level.getBlockState(fluidPos);
        net.minecraft.world.level.block.state.BlockState oldPour = level.getBlockState(pourPos);
        net.minecraft.world.level.block.state.BlockState oldCheer = level.getBlockState(cheerPos);
        net.minecraft.world.level.block.state.BlockState oldCheerSupport = level.getBlockState(cheerSupport);
        double oldX = player.getX(), oldY = player.getY(), oldZ = player.getZ();
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            // 眼高落在水源方块内部，朝正北的真实射线必经 fluidPos。
            player.setPos(fluidPos.getX() + 0.5D, fluidPos.getY() - 1.0D, fluidPos.getZ() + 3.5D);
            player.setYRot(180.0F);
            player.setXRot(0.0F);

            ItemStack bath = new ItemStack(ModItems.BATH_BUCKET.get());
            if (bath.getMaxDamage() != 10) throw new IllegalStateException("bath bucket durability is not ten");
            player.setItemInHand(InteractionHand.MAIN_HAND, bath);
            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            if (!bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.WATER) {
                throw new IllegalStateException("bath bucket did not retain water in its own stack");
            }

            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            if (!bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.EMPTY) {
                throw new IllegalStateException("bath bucket did not use vanilla-safe emptying semantics");
            }

            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
            if (!bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || bath.getDamageValue() != 1
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.LAVA) {
                throw new IllegalStateException("bath bucket lava pickup lost durability or container state");
            }

            ItemStack cup = new ItemStack(ModItems.PAPER_CUP.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, cup);
            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
            if (cup.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(cup) != net.minecraft.world.level.material.Fluids.EMPTY) {
                throw new IllegalStateException("paper cup accepted lava");
            }
            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            if (!cup.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(cup) != net.minecraft.world.level.material.Fluids.WATER) {
                throw new IllegalStateException("paper cup did not pick up water");
            }

            level.setBlock(cheerSupport, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(cheerPos, ModBlocks.BML_CHEER_STICK.get().defaultBlockState(), 3);
            net.minecraft.world.level.block.state.BlockState cheerState = level.getBlockState(cheerPos);
            ModBlocks.BML_CHEER_STICK.get().use(cheerState, level, cheerPos, player, InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(cheerPos), net.minecraft.core.Direction.UP, cheerPos, false));
            if (!level.getBlockState(cheerPos).getValue(BmlCheerStickBlock.LIT)) {
                throw new IllegalStateException("BML cheer stick did not switch LIT on the logical server");
            }
        } finally {
            level.setBlock(fluidPos, oldFluid, 3);
            level.setBlock(pourPos, oldPour, 3);
            level.setBlock(cheerPos, oldCheer, 3);
            level.setBlock(cheerSupport, oldCheerSupport, 3);
            player.setPos(oldX, oldY, oldZ);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** 004 的四个 UUID 必须不同，否则 Forge 会将两只手的同属性 Modifier 去重。 */
    private static void assertNailArt() {
        net.minecraft.world.item.Item nail = ModItems.NAIL_ART.get();
        net.minecraft.world.entity.EquipmentSlot mainHand = net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        net.minecraft.world.entity.EquipmentSlot offHand = net.minecraft.world.entity.EquipmentSlot.OFFHAND;
        if (!approximately(attributeTotal(nail, mainHand, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE), 1.0D)
                || !approximately(attributeTotal(nail, offHand, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE), 1.0D)
                || !approximately(attributeTotal(nail, mainHand, net.minecraftforge.common.ForgeMod.ENTITY_REACH.get()), 2.0D)
                || !approximately(attributeTotal(nail, offHand, net.minecraftforge.common.ForgeMod.ENTITY_REACH.get()), 2.0D)) {
            throw new IllegalStateException("nail art lacks +1 damage and +2 entity reach in both hands");
        }
        Set<UUID> modifierIds = new HashSet<>();
        nail.getDefaultAttributeModifiers(mainHand).values().forEach(modifier -> modifierIds.add(modifier.getId()));
        nail.getDefaultAttributeModifiers(offHand).values().forEach(modifier -> modifierIds.add(modifier.getId()));
        if (modifierIds.size() != 4) {
            throw new IllegalStateException("nail art reuses modifier UUIDs and cannot stack across hands");
        }
    }

    private static void assertHeadwear(ItemStack stack, String name) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor)
                || armor.getEquipmentSlot() != net.minecraft.world.entity.EquipmentSlot.HEAD) {
            throw new IllegalStateException(name + " is not equipable in the head armor slot");
        }
    }

    private static boolean approximately(double actual, double expected) {
        return Math.abs(actual - expected) <= 1.0E-6D;
    }

    private static void assertFood(net.minecraft.world.item.Item item, int nutrition, float saturation) {
        net.minecraft.world.food.FoodProperties food = item.getFoodProperties();
        if (food == null || food.getNutrition() != nutrition || Float.compare(food.getSaturationModifier(), saturation) != 0) {
            throw new IllegalStateException("food properties mismatch for " + item);
        }
    }

    private static void assertEffect(ServerPlayer player, net.minecraft.world.effect.MobEffect effect, int amplifier, int minimumDuration) {
        net.minecraft.world.effect.MobEffectInstance instance = player.getEffect(effect);
        if (instance == null || instance.getAmplifier() != amplifier || instance.getDuration() < minimumDuration) {
            throw new IllegalStateException("effect mismatch: " + effect);
        }
    }

    /** 两个真实客户端在线时，从服务端直接调用生产事务入口并断言多人安全语义。 */
    private static int runMultiBusiness(CommandSourceStack source) {
        try {
            List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();
            if (players.size() != 2) {
                source.sendFailure(Component.literal("CI 多人业务要求恰好两个在线玩家"));
                return 0;
            }
            ServerPlayer alice = players.stream().filter(player -> player.getGameProfile().getName().equals("BlindBoxAlice")).findFirst().orElseThrow();
            ServerPlayer bob = players.stream().filter(player -> player.getGameProfile().getName().equals("BlindBoxBob")).findFirst().orElseThrow();
            BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(source.getServer().overworld());
            if (!data.transactions().isEmpty() || !data.bundles().isEmpty() || !data.openReservations().isEmpty()) {
                source.sendFailure(Component.literal("CI 多人业务要求空奖池和空事务日志"));
                return 0;
            }

            // 菜单关闭、容器编号和会话 nonce 任一不匹配时，伪造/重放提交必须被拒绝。
            UUID menuSession = UUID.fromString("99999999-9999-9999-9999-999999999999");
            PackingMenu packingMenu = new PackingMenu(91, alice.getInventory(), menuSession);
            alice.containerMenu = packingMenu;
            CommitPackingPacket validShape = new CommitPackingPacket(91, menuSession, List.of());
            if (!CommitPackingPacket.isAuthorized(alice, validShape)) throw new IllegalStateException("valid packing session rejected");
            if (CommitPackingPacket.isAuthorized(alice, new CommitPackingPacket(92, menuSession, List.of()))) {
                throw new IllegalStateException("forged container id accepted");
            }
            if (CommitPackingPacket.isAuthorized(alice, new CommitPackingPacket(91, UUID.randomUUID(), List.of()))) {
                throw new IllegalStateException("forged packing nonce accepted");
            }
            int acceptedBurstPackets = 0;
            for (int attempt = 0; attempt < 64; attempt++) {
                if (CommitPackingPacket.authorizeAndConsume(alice, validShape)) acceptedBurstPackets++;
            }
            if (acceptedBurstPackets != 1 || !data.transactions().isEmpty() || !data.bundles().isEmpty()) {
                throw new IllegalStateException("packing burst replay was not bounded or changed assets");
            }
            alice.closeContainer();
            if (CommitPackingPacket.isAuthorized(alice, validShape)) throw new IllegalStateException("closed menu replay accepted");

            // 换手/松开走原版 releaseUsing；死亡走 Forge 事件入口。两条真实路径都必须同时清理使用态和减速。
            ItemStack swappedBox = BlindBoxService.createBlindBox(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            alice.setItemInHand(InteractionHand.MAIN_HAND, swappedBox);
            swappedBox.getItem().use(alice.serverLevel(), alice, InteractionHand.MAIN_HAND);
            if (!BlindBoxItem.hasActiveUseState(alice, swappedBox)) throw new IllegalStateException("swap opening state missing");
            swappedBox.getItem().releaseUsing(swappedBox, alice.serverLevel(), alice, 39);
            alice.stopUsingItem();
            if (BlindBoxItem.hasActiveUseState(alice, swappedBox) || alice.isUsingItem()) {
                throw new IllegalStateException("opening lifecycle state leaked after swap/release");
            }

            ItemStack deathBox = BlindBoxService.createBlindBox(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
            alice.setItemInHand(InteractionHand.MAIN_HAND, deathBox);
            deathBox.getItem().use(alice.serverLevel(), alice, InteractionHand.MAIN_HAND);
            if (!BlindBoxItem.hasActiveUseState(alice, deathBox)) throw new IllegalStateException("death opening state missing");
            ServerLifecycleEvents.death(new LivingDeathEvent(alice, alice.damageSources().generic()));
            alice.stopUsingItem();
            if (BlindBoxItem.hasActiveUseState(alice, deathBox) || alice.isUsingItem()) {
                throw new IllegalStateException("opening lifecycle state leaked after death event");
            }
            clearInventory(alice);

            ItemStack staleSource = uniqueStack("citest-stale-source", 2, 3);
            alice.getInventory().setItem(0, staleSource.copy());
            String staleFingerprint = StackFingerprint.of(alice.getInventory().getItem(0));
            alice.getInventory().getItem(0).setDamageValue(4);
            if (BlindBoxService.pack(alice, List.of(new BlindBoxService.Selection(0, 1, staleFingerprint)))) {
                throw new IllegalStateException("stale fingerprint was accepted");
            }
            if (alice.getInventory().getItem(0).getCount() != 2 || data.bundleCount() != 0 || !data.transactions().isEmpty()) {
                throw new IllegalStateException("stale pack changed assets");
            }

            fillInventory(alice, Items.COBBLESTONE);
            alice.getInventory().setItem(0, uniqueStack("citest-full-source", 2, 5));
            if (BlindBoxService.pack(alice, List.of(new BlindBoxService.Selection(0, 1, StackFingerprint.of(alice.getInventory().getItem(0)))))) {
                throw new IllegalStateException("full inventory pack was accepted");
            }
            if (alice.getInventory().getItem(0).getCount() != 2 || data.bundleCount() != 0 || !data.transactions().isEmpty()) {
                throw new IllegalStateException("full inventory pack changed assets");
            }

            clearInventory(alice);
            clearInventory(bob);
            ItemStack prize = uniqueStack("citest-last-bundle-prize", 1, 13);
            alice.getInventory().setItem(0, prize.copy());
            BlindBoxService.Selection packSelection = new BlindBoxService.Selection(0, 1, StackFingerprint.of(alice.getInventory().getItem(0)));
            if (!BlindBoxService.pack(alice, List.of(packSelection))) {
                throw new IllegalStateException("production pack failed");
            }
            // 同一 C2S 请求即使因重发再次进入服务端业务层，也必须被旧指纹拒绝，不能重复生成 bundle/token。
            if (BlindBoxService.pack(alice, List.of(packSelection))) {
                throw new IllegalStateException("duplicate packing request was accepted");
            }
            if (data.bundleCount() != 1 || data.transactions().size() != 1 || countBlindBoxes(alice) != 1) {
                throw new IllegalStateException("duplicate packing request changed conserved assets");
            }
            ItemStack aliceBox = findBlindBox(alice);
            if (aliceBox.isEmpty()) throw new IllegalStateException("Alice did not receive blind box");
            ItemStack bobBox = BlindBoxService.createBlindBox(UUID.fromString("88888888-8888-8888-8888-888888888888"));
            bob.getInventory().setItem(0, bobBox);
            if (!BlindBoxService.open(alice, aliceBox)) throw new IllegalStateException("first open failed");
            if (BlindBoxService.open(bob, bobBox)) throw new IllegalStateException("second player opened exhausted pool");
            if (data.bundleCount() != 0 || countMarker(alice, "citest-last-bundle-prize") != 1 || countMarker(bob, "citest-last-bundle-prize") != 0) {
                throw new IllegalStateException("last bundle competition violated asset conservation");
            }
            if (!bob.getInventory().getItem(0).is(ModItems.BLIND_BOX.get()) || bob.getInventory().getItem(0).getCount() != 1) {
                throw new IllegalStateException("failed open consumed Bob token");
            }
            int transactionCountAfterCompetition = data.transactions().size();
            if (BlindBoxService.open(bob, bobBox)) throw new IllegalStateException("duplicate exhausted-pool open was accepted");
            if (data.transactions().size() != transactionCountAfterCompetition || data.bundleCount() != 0
                    || !bob.getInventory().getItem(0).is(ModItems.BLIND_BOX.get()) || bob.getInventory().getItem(0).getCount() != 1) {
                throw new IllegalStateException("duplicate open request changed conserved assets");
            }
            if (data.transactions().size() != 2 || data.transactions().stream().anyMatch(record -> record.stage() != TransactionRecord.Stage.COMMITTED)) {
                throw new IllegalStateException("unexpected transaction terminal state");
            }
            alice.containerMenu.broadcastChanges();
            bob.containerMenu.broadcastChanges();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_MULTI_BUSINESS=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 多人业务失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run multi-client business suite", exception);
            return 0;
        }
    }

    private static void fillInventory(ServerPlayer player, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(item, 64));
    }

    private static void clearInventory(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
        player.getInventory().offhand.set(0, ItemStack.EMPTY);
        player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    private static ItemStack findBlindBoxByToken(ServerPlayer player, UUID token) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (hasToken(stack, token)) return stack;
        }
        ItemStack offhand = player.getOffhandItem();
        return hasToken(offhand, token) ? offhand : ItemStack.EMPTY;
    }

    private static boolean hasToken(ItemStack stack, UUID token) {
        return stack.is(ModItems.BLIND_BOX.get()) && stack.hasTag() && stack.getTag().hasUUID(BlindBoxService.TOKEN_KEY)
                && token.equals(stack.getTag().getUUID(BlindBoxService.TOKEN_KEY));
    }

    private static ItemStack findBlindBox(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.BLIND_BOX.get())) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static int countBlindBoxes(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.BLIND_BOX.get())) count += stack.getCount();
        }
        return count;
    }

    private static int countMarker(ServerPlayer player, String marker) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.hasTag() && marker.equals(stack.getTag().getString("blindbox_citest_marker"))) count += stack.getCount();
        }
        return count;
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
