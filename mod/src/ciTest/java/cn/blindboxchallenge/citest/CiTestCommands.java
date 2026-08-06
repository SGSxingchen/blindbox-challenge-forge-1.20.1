package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.capability.ModCapabilities;
import cn.blindboxchallenge.event.ServerLifecycleEvents;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.item.BlackKnightTelescopicKnifeItem;
import cn.blindboxchallenge.item.PurpleToyPickaxeSwordItem;
import cn.blindboxchallenge.item.VodkaItem;
import cn.blindboxchallenge.item.HeadphonesItem;
import cn.blindboxchallenge.item.SafetyExitSignShieldItem;
import cn.blindboxchallenge.item.DecisionCoinItem;
import cn.blindboxchallenge.item.BirthdayCandleItem;
import cn.blindboxchallenge.item.RainbowHoopItem;
import cn.blindboxchallenge.service.PlayerAbilityService;
import cn.blindboxchallenge.service.PigBreedingService;
import cn.blindboxchallenge.config.ModServerConfig;
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
                .then(Commands.literal("run_p3_business").executes(context -> runP3Business(context.getSource())))
                .then(Commands.literal("start_p3_pig_clients")
                        .executes(context -> P3PigBreedingCiScenario.start(context.getSource())))
                .then(Commands.literal("verify_p3_pig_clients")
                        .executes(context -> P3PigBreedingCiScenario.verifyClientMarkers(context.getSource())))
                .then(Commands.literal("cleanup_p3_pig_clients")
                        .executes(context -> P3PigBreedingCiScenario.cleanup(context.getSource())))
                .then(Commands.literal("run_p3_pillow").executes(context -> PillowCiScenario.start(context.getSource())))
                .then(Commands.literal("verify_p3_pillow_clients")
                        .executes(context -> PillowCiScenario.verifyClientMarkers(context.getSource())))
                .then(Commands.literal("run_p3_scissors")
                        .executes(context -> ReturningScissorsCiScenario.start(context.getSource())))
                .then(Commands.literal("verify_p3_scissors_clients")
                        .executes(context -> ReturningScissorsCiScenario.verifyClientMarkers(context.getSource())))
                .then(Commands.literal("start_p3_ability_clients")
                        .executes(context -> P3AbilityCiScenario.startClientPath(context.getSource())))
                .then(Commands.literal("verify_p3_ability_clients")
                        .executes(context -> P3AbilityCiScenario.verifyClientPath(context.getSource())))
                .then(Commands.literal("start_p3_ability_clone")
                        .executes(context -> P3AbilityCiScenario.startDeathClone(context.getSource())))
                .then(Commands.literal("start_p3_ability_dimension")
                        .executes(context -> P3AbilityCiScenario.startDimensionChange(context.getSource())))
                .then(Commands.literal("verify_p3_ability_lifecycle_client")
                        .executes(context -> P3AbilityCiScenario.verifyLifecycleClient(context.getSource())))
                .then(Commands.literal("verify_p3_ability_recovery")
                        .executes(context -> P3AbilityCiScenario.verifyAfterRecovery(context.getSource())))
                .then(Commands.literal("cleanup_p3_ability")
                        .executes(context -> P3AbilityCiScenario.cleanup(context.getSource())))
                .then(Commands.literal("prepare_reconnect").executes(context -> prepareReconnect(context.getSource())))
                .then(Commands.literal("verify_reconnect").executes(context -> verifyReconnect(context.getSource()))));
    }

    private static final UUID RECONNECT_TOKEN = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static int prepareReconnect(CommandSourceStack source) {
        try {
            ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (alice == null) throw new IllegalStateException("Alice not online before reconnect test");
            if (!PlayerAbilityService.learnYiJin(alice)) {
                throw new IllegalStateException("P3 reconnect fixture could not learn Yi Jin exactly once");
            }
            assertYiJinAttributes(alice);
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
            var capability = alice.getCapability(ModCapabilities.PLAYER_ABILITY).resolve()
                    .orElseThrow(() -> new IllegalStateException("P3 reconnect capability missing after player reload"));
            if (!capability.hasLearnedYiJin()) {
                throw new IllegalStateException("Yi Jin learned state did not survive real disconnect/reconnect");
            }
            assertYiJinAttributes(alice);
            capability.setLearnedYiJin(false);
            capability.setUsedDoubleJump(false);
            capability.setNextDoubleJumpTick(0L);
            PlayerAbilityService.reconcileAttributes(alice, capability);
            PlayerAbilityService.syncTrackingAndSelf(alice, capability);
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

            assertTransformingWeapons(player);
            assertContainersAndLighting(player);
            assertRemainingP2Items(source, player);

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

    /** P3 第一批：真实逻辑服务端的易筋经状态、固定属性与二段跳拒绝/允许路径。 */
    private static int runP3Business(CommandSourceStack source) {
        ServerPlayer player = null;
        ItemStack originalMainHand = ItemStack.EMPTY;
        float originalHealth = 0.0F;
        net.minecraft.world.phys.Vec3 originalVelocity = net.minecraft.world.phys.Vec3.ZERO;
        boolean originalOnGround = false;
        float originalFallDistance = 0.0F;
        boolean originalHurtMarked = false;
        boolean originalLearned = false;
        boolean originalUsedDoubleJump = false;
        long originalNextDoubleJumpTick = 0L;
        try {
            player = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
            if (player == null) throw new IllegalStateException("Alice not online for P3 suite");
            originalMainHand = player.getMainHandItem().copy();
            originalHealth = player.getHealth();
            originalVelocity = player.getDeltaMovement();
            originalOnGround = player.onGround();
            originalFallDistance = player.fallDistance;
            originalHurtMarked = player.hurtMarked;
            var capability = player.getCapability(ModCapabilities.PLAYER_ABILITY).resolve()
                    .orElseThrow(() -> new IllegalStateException("P3 player capability was not attached"));
            originalLearned = capability.hasLearnedYiJin();
            originalUsedDoubleJump = capability.hasUsedDoubleJump();
            originalNextDoubleJumpTick = capability.nextDoubleJumpTick();

            capability.setLearnedYiJin(false);
            capability.setUsedDoubleJump(false);
            capability.setNextDoubleJumpTick(0L);
            PlayerAbilityService.reconcileAttributes(player, capability);
            player.setOnGround(false);
            if (PlayerAbilityService.requestDoubleJump(player)) {
                throw new IllegalStateException("unlearned player was allowed to double jump");
            }

            ItemStack manual = new ItemStack(ModItems.YIJIN_MANUAL.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, manual);
            if (!manual.getItem().use(player.serverLevel(), player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || !manual.isEmpty() || !capability.hasLearnedYiJin()) {
                throw new IllegalStateException("first Yi Jin manual use did not learn and consume exactly once");
            }
            assertYiJinAttributes(player);

            ItemStack duplicateManual = new ItemStack(ModItems.YIJIN_MANUAL.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, duplicateManual);
            if (duplicateManual.getItem().use(player.serverLevel(), player, InteractionHand.MAIN_HAND).getResult()
                    != net.minecraft.world.InteractionResult.FAIL || duplicateManual.getCount() != 1) {
                throw new IllegalStateException("duplicate Yi Jin manual use was not rejected without consumption");
            }

            player.setDeltaMovement(0.12D, -0.18D, -0.08D);
            if (!PlayerAbilityService.requestDoubleJump(player)
                    || !approximately(player.getDeltaMovement().x, 0.12D)
                    || !approximately(player.getDeltaMovement().z, -0.08D)
                    || !approximately(player.getDeltaMovement().y, PlayerAbilityService.DOUBLE_JUMP_VELOCITY)
                    || PlayerAbilityService.requestDoubleJump(player)) {
                throw new IllegalStateException("server double-jump permission, velocity or one-air-use limit mismatch");
            }
            if (!capability.isDoubleJumpOnCooldown(player.serverLevel().getGameTime())) {
                throw new IllegalStateException("server double-jump did not write a personal cooldown");
            }
            player.setOnGround(true);
            PlayerAbilityService.resetAirJumpWhenGrounded(player);
            player.setOnGround(false);
            if (PlayerAbilityService.requestDoubleJump(player)) {
                throw new IllegalStateException("server double-jump cooldown was bypassed after grounding");
            }
            capability.setNextDoubleJumpTick(player.serverLevel().getGameTime());
            if (!PlayerAbilityService.requestDoubleJump(player)) {
                throw new IllegalStateException("server grounding did not reset one-air double-jump permission after cooldown");
            }

            ItemStack roadBarrier = new ItemStack(ModItems.ROAD_BARRIER_HELMET.get());
            ItemStack ironHelmet = new ItemStack(Items.IRON_HELMET);
            if (!(roadBarrier.getItem() instanceof cn.blindboxchallenge.item.RoadBarrierHelmetItem)
                    || roadBarrier.getMaxDamage() != ironHelmet.getMaxDamage()
                    || !approximately(stackAttributeTotal(roadBarrier, net.minecraft.world.entity.EquipmentSlot.HEAD,
                            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                            stackAttributeTotal(ironHelmet, net.minecraft.world.entity.EquipmentSlot.HEAD,
                                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR))
                    || !approximately(stackAttributeTotal(roadBarrier, net.minecraft.world.entity.EquipmentSlot.HEAD,
                            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS),
                            stackAttributeTotal(ironHelmet, net.minecraft.world.entity.EquipmentSlot.HEAD,
                                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS))) {
                throw new IllegalStateException("road barrier helmet differs from vanilla iron helmet attributes or durability");
            }

            assertEfficientPigBreeding(player);

            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_BUSINESS=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI P3 业务失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P3 business suite", exception);
            return 0;
        } finally {
            if (player != null) {
                player.setItemInHand(InteractionHand.MAIN_HAND, originalMainHand);
                player.setHealth(Math.min(originalHealth, player.getMaxHealth()));
                player.setDeltaMovement(originalVelocity);
                player.setOnGround(originalOnGround);
                player.fallDistance = originalFallDistance;
                player.hurtMarked = originalHurtMarked;
                ServerPlayer restoredPlayer = player;
                boolean restoredLearned = originalLearned;
                boolean restoredUsedDoubleJump = originalUsedDoubleJump;
                long restoredNextDoubleJumpTick = originalNextDoubleJumpTick;
                restoredPlayer.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
                    data.setLearnedYiJin(restoredLearned);
                    data.setUsedDoubleJump(restoredUsedDoubleJump);
                    data.setNextDoubleJumpTick(restoredNextDoubleJumpTick);
                    PlayerAbilityService.reconcileAttributes(restoredPlayer, data);
                    PlayerAbilityService.syncTrackingAndSelf(restoredPlayer, data);
                });
                player.containerMenu.broadcastChanges();
            }
        }
    }

    private static void assertYiJinAttributes(ServerPlayer player) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance health = player.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        net.minecraft.world.entity.ai.attributes.AttributeInstance attack = player.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (health == null || attack == null || health.getModifier(PlayerAbilityService.YIJIN_MAX_HEALTH_UUID) == null
                || attack.getModifier(PlayerAbilityService.YIJIN_ATTACK_DAMAGE_UUID) == null
                || !approximately(health.getModifier(PlayerAbilityService.YIJIN_MAX_HEALTH_UUID).getAmount(),
                        PlayerAbilityService.YIJIN_MAX_HEALTH_BONUS)
                || !approximately(attack.getModifier(PlayerAbilityService.YIJIN_ATTACK_DAMAGE_UUID).getAmount(),
                        PlayerAbilityService.YIJIN_ATTACK_DAMAGE_BONUS)) {
            throw new IllegalStateException("Yi Jin capability did not reconcile its fixed UUID attributes");
        }
    }

    /** 011：真实服务端书本入口、10 格球形过滤、候选硬上限、繁殖与冷却。 */
    private static void assertEfficientPigBreeding(ServerPlayer player) {
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        double originalX = player.getX();
        double originalY = player.getY();
        double originalZ = player.getZ();
        float originalYRot = player.getYRot();
        float originalXRot = player.getXRot();
        if (player.getCooldowns().isOnCooldown(ModItems.EFFICIENT_PIG_BREEDING.get())) {
            throw new IllegalStateException("pig breeding fixture was not clean before server cooldown assertion");
        }
        // 高空夹具区与玩家保持原有 X/Z 柱，只上移到 y=200。原 X/Z 已由在线玩家
        // 保持为完整加载，避免横向传送到新区块后同 tick 新实体尚未进入实体分区；
        // 高度仍足以隔离自然猪，结束后精确回到原位置。
        net.minecraft.core.BlockPos fixtureCenter = net.minecraft.core.BlockPos.containing(
                originalX, 200.0D, originalZ);
        player.teleportTo(level, fixtureCenter.getX() + 0.5D, fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D,
                originalYRot, originalXRot);
        net.minecraft.world.phys.AABB fixtureArea = new net.minecraft.world.phys.AABB(fixtureCenter).inflate(12.0D);
        Set<UUID> fixturePigs = new HashSet<>();
        try {
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class, fixtureArea).isEmpty()) {
                throw new IllegalStateException("pig breeding fixture area was occupied before test execution");
            }
            // 先直接调用生产服务，明确证明 AABB 粗筛之后仍执行了 10 格球形过滤。
            net.minecraft.world.entity.animal.Pig first = spawnFixturePig(level, fixtureCenter.getX() + 2.5D,
                    fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D);
            net.minecraft.world.entity.animal.Pig second = spawnFixturePig(level, fixtureCenter.getX() - 1.5D,
                    fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D);
            fixturePigs.add(first.getUUID());
            fixturePigs.add(second.getUUID());
            // +10.1 仍落在玩家 AABB inflate(10) 的粗筛边缘，但必在精确 10 格球形之外。
            net.minecraft.world.entity.animal.Pig outsideSphere = spawnFixturePig(level, fixtureCenter.getX() + 10.6D,
                    fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D);
            fixturePigs.add(outsideSphere.getUUID());
            PigBreedingService.BreedingResult spherical = PigBreedingService.breedNearby(player);
            if (spherical.scannedPigCount() != 2 || spherical.eligiblePigCount() != 2 || spherical.bredPairCount() != 1
                    || !outsideSphere.canFallInLove()) {
                throw new IllegalStateException("pig breeding sphere mismatch: scanned=" + spherical.scannedPigCount()
                        + ", eligible=" + spherical.eligiblePigCount() + ", pairs=" + spherical.bredPairCount()
                        + ", outside_can_love=" + outsideSphere.canFallInLove());
            }
            collectFixturePigChildren(level, fixtureArea, fixturePigs);
            discardFixturePigs(level, fixtureArea, fixturePigs);
            fixturePigs.clear();

            // 再走物品生产入口，验证书不消耗、成功冷却和重复拒绝。
            net.minecraft.world.entity.animal.Pig bookFirst = spawnFixturePig(level, fixtureCenter.getX() + 2.5D,
                    fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D);
            net.minecraft.world.entity.animal.Pig bookSecond = spawnFixturePig(level, fixtureCenter.getX() - 1.5D,
                    fixtureCenter.getY(), fixtureCenter.getZ() + 0.5D);
            fixturePigs.add(bookFirst.getUUID());
            fixturePigs.add(bookSecond.getUUID());
            ItemStack book = new ItemStack(ModItems.EFFICIENT_PIG_BREEDING.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            if (!book.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || book.getCount() != 1 || !player.getCooldowns().isOnCooldown(ModItems.EFFICIENT_PIG_BREEDING.get())) {
                throw new IllegalStateException("efficient pig breeding book did not preserve book stack or write server cooldown");
            }
            collectFixturePigChildren(level, fixtureArea, fixturePigs);
            if (fixturePigs.size() != 3) throw new IllegalStateException("pig breeding book did not create exactly one fixture child");
            if (book.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult()
                    != net.minecraft.world.InteractionResult.FAIL) {
                throw new IllegalStateException("pig breeding server cooldown did not reject repeated use");
            }
            player.getCooldowns().removeCooldown(ModItems.EFFICIENT_PIG_BREEDING.get());
            discardFixturePigs(level, fixtureArea, fixturePigs);
            fixturePigs.clear();

            int maximum = ModServerConfig.EFFICIENT_PIG_MAX_SCANNED.get();
            for (int gridX = -9; gridX <= 9 && fixturePigs.size() < maximum + 2; gridX++) {
                for (int gridZ = -9; gridZ <= 9 && fixturePigs.size() < maximum + 2; gridZ++) {
                    if (gridX * gridX + gridZ * gridZ > 81) continue;
                    net.minecraft.world.entity.animal.Pig pig = spawnFixturePig(level,
                            fixtureCenter.getX() + gridX + 0.75D, fixtureCenter.getY(),
                            fixtureCenter.getZ() + gridZ + 0.75D);
                    fixturePigs.add(pig.getUUID());
                }
            }
            if (fixturePigs.size() != maximum + 2) {
                throw new IllegalStateException("pig breeding upper-bound fixture could not fit all legal config candidates");
            }
            PigBreedingService.BreedingResult capped = PigBreedingService.breedNearby(player);
            if (capped.scannedPigCount() != maximum || capped.eligiblePigCount() != maximum
                    || capped.bredPairCount() != maximum / 2) {
                throw new IllegalStateException("pig breeding scan upper bound or paired production result mismatch");
            }
            collectFixturePigChildren(level, fixtureArea, fixturePigs);
        } finally {
            player.getCooldowns().removeCooldown(ModItems.EFFICIENT_PIG_BREEDING.get());
            discardFixturePigs(level, fixtureArea, fixturePigs);
            player.teleportTo(level, originalX, originalY, originalZ, originalYRot, originalXRot);
        }
    }

    private static void collectFixturePigChildren(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.AABB area,
                                                  Set<UUID> fixturePigs) {
        level.getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class, area).stream()
                .map(net.minecraft.world.entity.Entity::getUUID).forEach(fixturePigs::add);
    }

    private static void discardFixturePigs(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.AABB area,
                                           Set<UUID> fixturePigs) {
        level.getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class, area,
                pig -> fixturePigs.contains(pig.getUUID())).forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static net.minecraft.world.entity.animal.Pig spawnFixturePig(net.minecraft.server.level.ServerLevel level,
                                                                           double x, double y, double z) {
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("could not create pig breeding fixture entity");
        pig.setAge(0);
        pig.setPos(x, y, z);
        if (!level.addFreshEntity(pig)) {
            throw new IllegalStateException("pig breeding fixture entity was not added to the loaded server world");
        }
        return pig;
    }

    private static double attributeTotal(net.minecraft.world.item.Item item, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return attributeTotal(item, net.minecraft.world.entity.EquipmentSlot.MAINHAND, attribute);
    }

    private static double attributeTotal(net.minecraft.world.item.Item item, net.minecraft.world.entity.EquipmentSlot slot,
                                         net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return item.getDefaultAttributeModifiers(slot)
                .get(attribute).stream().mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
    }

    /** 第八批：两个形态都必须在两个真实客户端在线的逻辑服务端上走生产入口。 */
    private static void assertTransformingWeapons(ServerPlayer player) {
        net.minecraft.server.level.ServerLevel level = player.serverLevel();

        ItemStack knife = new ItemStack(ModItems.BLACK_KNIGHT_TELESCOPIC_KNIFE.get());
        double knifeDamage = attributeTotal(ModItems.BLACK_KNIGHT_TELESCOPIC_KNIFE.get(),
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        double knifeSpeed = attributeTotal(ModItems.BLACK_KNIGHT_TELESCOPIC_KNIFE.get(),
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        if (knife.getMaxDamage() != net.minecraft.world.item.Tiers.WOOD.getUses()
                || !approximately(knifeDamage, 3.0D) || !approximately(knifeSpeed, -2.4D)
                || BlackKnightTelescopicKnifeItem.isExtended(knife)) {
            throw new IllegalStateException("黑武士伸缩刀不符合收缩状态的原版木剑基线");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, knife);
        if (!knife.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                || !knife.hasTag() || !knife.getTag().contains(BlackKnightTelescopicKnifeItem.EXTENDED_KEY, net.minecraft.nbt.Tag.TAG_BYTE)
                || !BlackKnightTelescopicKnifeItem.isExtended(knife)) {
            throw new IllegalStateException("黑武士伸缩刀的服务端右键未伸出刀刃");
        }
        if (!knife.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                || !knife.hasTag() || !knife.getTag().contains(BlackKnightTelescopicKnifeItem.EXTENDED_KEY, net.minecraft.nbt.Tag.TAG_BYTE)
                || BlackKnightTelescopicKnifeItem.isExtended(knife)) {
            throw new IllegalStateException("黑武士伸缩刀的服务端右键未收缩刀刃");
        }
        if (Float.compare(BlackKnightTelescopicKnifeItem.AUTO_RETRACT_CHANCE, 0.20F) != 0) {
            throw new IllegalStateException("黑武士伸缩刀未使用约定的保守固定收缩概率");
        }
        ItemStack trialKnife = new ItemStack(ModItems.BLACK_KNIGHT_TELESCOPIC_KNIFE.get());
        BlackKnightTelescopicKnifeItem.setExtended(trialKnife, true);
        // 一次真实服务端命中钩子保留木剑耐久；固定随机规则另以确定性边界值验证，不能引入随机门禁。
        trialKnife.getItem().hurtEnemy(trialKnife, player, player);
        if (trialKnife.getDamageValue() != 1) {
            throw new IllegalStateException("黑武士伸缩刀未保留原版木剑命中耐久");
        }
        BlackKnightTelescopicKnifeItem.setExtended(trialKnife, true);
        if (!BlackKnightTelescopicKnifeItem.applyAutoRetractAfterHit(trialKnife, 0.0F)
                || BlackKnightTelescopicKnifeItem.isExtended(trialKnife)) {
            throw new IllegalStateException("黑武士伸缩刀未按服务端固定概率收缩");
        }
        BlackKnightTelescopicKnifeItem.setExtended(trialKnife, true);
        if (BlackKnightTelescopicKnifeItem.applyAutoRetractAfterHit(trialKnife,
                BlackKnightTelescopicKnifeItem.AUTO_RETRACT_CHANCE)
                || !BlackKnightTelescopicKnifeItem.isExtended(trialKnife)) {
            throw new IllegalStateException("黑武士伸缩刀错误处理了概率阈值边界");
        }

        ItemStack purpleToy = new ItemStack(ModItems.PURPLE_TOY_PICKAXE_SWORD.get());
        if (purpleToy.getMaxDamage() != net.minecraft.world.item.Tiers.WOOD.getUses()
                || !PurpleToyPickaxeSwordItem.isPickaxeForm(purpleToy)
                || !purpleToy.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                || purpleToy.canPerformAction(net.minecraftforge.common.ToolActions.SWORD_DIG)
                || purpleToy.getDestroySpeed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                    != net.minecraft.world.item.Tiers.WOOD.getSpeed()
                || !purpleToy.isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                || purpleToy.isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState())
                || !approximately(stackAttributeTotal(purpleToy, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE), 1.0D)
                || !approximately(stackAttributeTotal(purpleToy, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED), -2.8D)) {
            throw new IllegalStateException("紫色玩具钻石镐缺少原版木镐的栈敏感采掘或属性语义");
        }

        player.setItemInHand(InteractionHand.MAIN_HAND, purpleToy);
        if (!purpleToy.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                || !purpleToy.hasTag() || !purpleToy.getTag().contains(PurpleToyPickaxeSwordItem.PICKAXE_FORM_KEY, net.minecraft.nbt.Tag.TAG_BYTE)
                || PurpleToyPickaxeSwordItem.isPickaxeForm(purpleToy)
                || purpleToy.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                || !purpleToy.canPerformAction(net.minecraftforge.common.ToolActions.SWORD_DIG)
                || !purpleToy.canPerformAction(net.minecraftforge.common.ToolActions.SWORD_SWEEP)
                || purpleToy.getDestroySpeed(net.minecraft.world.level.block.Blocks.COBWEB.defaultBlockState()) != 15.0F
                || !approximately(stackAttributeTotal(purpleToy, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE), 3.0D)
                || !approximately(stackAttributeTotal(purpleToy, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED), -2.4D)) {
            throw new IllegalStateException("紫色玩具钻石剑缺少原版木剑的栈敏感近战语义");
        }
        if (!purpleToy.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                || !purpleToy.hasTag() || !purpleToy.getTag().contains(PurpleToyPickaxeSwordItem.PICKAXE_FORM_KEY, net.minecraft.nbt.Tag.TAG_BYTE)
                || !PurpleToyPickaxeSwordItem.isPickaxeForm(purpleToy)) {
            throw new IllegalStateException("紫色玩具工具未由服务端写回镐形态 NBT");
        }
    }

    /**
     * P2 最后六项：在两客户端在线的逻辑服务端走生产入口。随机分支用生产共用的固定输入穷尽，
     * 而真实随机入口只验证其结果属于已声明分支，不能使 CI 依赖概率。
     */
    private static void assertRemainingP2Items(CommandSourceStack source, ServerPlayer player) {
        ServerPlayer bob = source.getServer().getPlayerList().getPlayerByName("BlindBoxBob");
        if (bob == null) throw new IllegalStateException("Bob not online for final P2 item suite");

        ItemStack playerMain = player.getMainHandItem().copy();
        ItemStack playerOff = player.getOffhandItem().copy();
        List<net.minecraft.world.effect.MobEffectInstance> playerEffects = player.getActiveEffects().stream()
                .map(net.minecraft.world.effect.MobEffectInstance::new).toList();
        float playerHealth = player.getHealth();
        net.minecraft.world.phys.Vec3 playerVelocity = player.getDeltaMovement();
        boolean playerHurtMarked = player.hurtMarked;
        ItemStack bobMain = bob.getMainHandItem().copy();
        ItemStack bobOff = bob.getOffhandItem().copy();
        List<net.minecraft.world.effect.MobEffectInstance> bobEffects = bob.getActiveEffects().stream()
                .map(net.minecraft.world.effect.MobEffectInstance::new).toList();
        float bobHealth = bob.getHealth();
        net.minecraft.world.phys.Vec3 bobVelocity = bob.getDeltaMovement();
        boolean bobHurtMarked = bob.hurtMarked;
        try {
            net.minecraft.server.level.ServerLevel level = player.serverLevel();
            if (player.isUsingItem() || bob.isUsingItem()
                    || player.getCooldowns().isOnCooldown(ModItems.HEADPHONES.get())
                    || player.getCooldowns().isOnCooldown(ModItems.BIRTHDAY_CANDLE.get())) {
                throw new IllegalStateException("final P2 fixture was not clean before cooldown/use-state assertions");
            }

            ItemStack vodka = new ItemStack(ModItems.VODKA.get(), 2);
            ModItems.VODKA.get().finishUsingItem(vodka, level, player);
            if (vodka.getCount() != 1 || vodka.getMaxStackSize() != 16) {
                throw new IllegalStateException("vodka was not consumed exactly once");
            }
            assertEffect(player, net.minecraft.world.effect.MobEffects.CONFUSION, 0,
                    VodkaItem.DRUNK_DURATION_TICKS - 10);
            player.removeAllEffects();

            ItemStack headphones = new ItemStack(ModItems.HEADPHONES.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, headphones);
            if (!headphones.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || !player.getCooldowns().isOnCooldown(ModItems.HEADPHONES.get())
                    || headphones.getCount() != 1 || headphones.getMaxStackSize() != 1) {
                throw new IllegalStateException("headphones lack server-original-sound cooldown semantics");
            }
            if (headphones.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult()
                    != net.minecraft.world.InteractionResult.FAIL) {
                throw new IllegalStateException("headphones cooldown did not reject repeated server request");
            }
            player.getCooldowns().removeCooldown(ModItems.HEADPHONES.get());

            ItemStack shield = new ItemStack(ModItems.SAFETY_EXIT_SIGN_SHIELD.get());
            if (shield.getMaxDamage() != SafetyExitSignShieldItem.DURABILITY
                    || !approximately(SafetyExitSignShieldItem.reflectedDamage(8.0F), 4.0D)
                    || SafetyExitSignShieldItem.reflectedDamage(-1.0F) != 0.0F) {
                throw new IllegalStateException("safety exit shield durability or reflection ratio mismatch");
            }
            player.removeAllEffects();
            bob.removeAllEffects();
            player.setHealth(player.getMaxHealth());
            bob.setHealth(bob.getMaxHealth());
            player.setItemInHand(InteractionHand.OFF_HAND, shield);
            player.startUsingItem(InteractionHand.OFF_HAND);
            float bobBeforeReflection = bob.getHealth();
            ServerLifecycleEvents.shieldBlock(new net.minecraftforge.event.entity.living.ShieldBlockEvent(player,
                    bob.damageSources().playerAttack(bob), 4.0F));
            if (!(bob.getHealth() < bobBeforeReflection)) {
                throw new IllegalStateException("safety exit shield event adapter did not damage the direct server-side attacker");
            }
            // 反伤调用栈结束后允许下一次格挡，证明防递归状态没有泄漏。
            bob.setHealth(bob.getMaxHealth());
            if (!approximately(ServerLifecycleEvents.reflectSuccessfulShieldBlock(player,
                    bob.damageSources().playerAttack(bob), 2.0F), 1.0D)) {
                throw new IllegalStateException("safety exit shield reflection guard leaked after one successful block");
            }
            player.stopUsingItem();

            ItemStack decisionCoin = new ItemStack(ModItems.DECISION_COIN.get(), 2);
            player.setItemInHand(InteractionHand.MAIN_HAND, decisionCoin);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 100, 0));
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 100, 0));
            if (!decisionCoin.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || decisionCoin.getCount() != 1) {
                throw new IllegalStateException("decision coin was not consumed by the server use path");
            }
            boolean randomHeads = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST) != null
                    && player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED) != null
                    && player.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS) != null;
            boolean randomTails = player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED) == null
                    && player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST) == null
                    && player.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS) != null;
            if (!randomHeads && !randomTails) {
                throw new IllegalStateException("decision coin server random outcome was outside declared heads/tails branches");
            }
            DecisionCoinItem.applyOutcome(player, true);
            assertEffect(player, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 1,
                    DecisionCoinItem.STRENGTH_DURATION_TICKS - 10);
            DecisionCoinItem.applyOutcome(player, false);
            if (player.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS) == null
                    || player.getActiveEffects().stream().anyMatch(effect -> effect.getEffect().isBeneficial())) {
                throw new IllegalStateException("decision coin tails did not clear only beneficial self effects");
            }
            player.removeAllEffects();

            ItemStack candle = new ItemStack(ModItems.BIRTHDAY_CANDLE.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, candle);
            if (!candle.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || candle.getMaxStackSize() != 1) {
                throw new IllegalStateException("birthday candle did not start its long-use server path");
            }
            ModItems.BIRTHDAY_CANDLE.get().finishUsingItem(candle, level, player);
            if (!player.getCooldowns().isOnCooldown(ModItems.BIRTHDAY_CANDLE.get())) {
                throw new IllegalStateException("birthday candle did not write its server cooldown");
            }
            boolean candleAppliedPositiveEffect = false;
            for (net.minecraft.world.effect.MobEffect effect : List.of(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,
                    net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
                    net.minecraft.world.effect.MobEffects.REGENERATION)) {
                net.minecraft.world.effect.MobEffectInstance instance = player.getEffect(effect);
                candleAppliedPositiveEffect |= instance != null && instance.getAmplifier() == 0
                        && instance.getDuration() >= BirthdayCandleItem.EFFECT_DURATION_TICKS - 10;
            }
            if (!candleAppliedPositiveEffect) throw new IllegalStateException("birthday candle did not apply a declared positive effect");
            player.getCooldowns().removeCooldown(ModItems.BIRTHDAY_CANDLE.get());
            player.removeAllEffects();
            for (int roll = 0; roll < BirthdayCandleItem.positiveEffectCount(); roll++) {
                BirthdayCandleItem.applyPositiveEffect(player, roll);
                long active = player.getActiveEffects().stream().filter(effect -> effect.getDuration()
                        >= BirthdayCandleItem.EFFECT_DURATION_TICKS - 10 && effect.getAmplifier() == 0).count();
                if (active != 1L) throw new IllegalStateException("birthday candle deterministic positive-effect branch missing: " + roll);
                player.removeAllEffects();
            }

            ItemStack hoop = new ItemStack(ModItems.RAINBOW_HOOP.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, hoop);
            player.setDeltaMovement(0.13D, -0.2D, -0.14D);
            hoop.getItem().releaseUsing(hoop, level, player, hoop.getItem().getUseDuration(hoop)
                    - RainbowHoopItem.MAX_CHARGE_TICKS);
            net.minecraft.world.phys.Vec3 launched = player.getDeltaMovement();
            if (!approximately(launched.x, 0.13D) || !approximately(launched.z, -0.14D)
                    || !approximately(launched.y, RainbowHoopItem.MAX_LAUNCH_VELOCITY)
                    || RainbowHoopItem.launchVelocity(RainbowHoopItem.MIN_CHARGE_TICKS - 1) != 0.0F
                    || !approximately(RainbowHoopItem.launchVelocity(RainbowHoopItem.MIN_CHARGE_TICKS),
                            RainbowHoopItem.MIN_LAUNCH_VELOCITY)
                    || !approximately(RainbowHoopItem.launchVelocity(RainbowHoopItem.MAX_CHARGE_TICKS + 100),
                            RainbowHoopItem.MAX_LAUNCH_VELOCITY)) {
                throw new IllegalStateException("rainbow hoop server-side charge velocity bounds mismatch");
            }
        } finally {
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, playerMain);
            player.setItemInHand(InteractionHand.OFF_HAND, playerOff);
            player.removeAllEffects();
            for (net.minecraft.world.effect.MobEffectInstance effect : playerEffects) player.addEffect(effect);
            player.setHealth(Math.min(playerHealth, player.getMaxHealth()));
            player.setDeltaMovement(playerVelocity);
            player.hurtMarked = playerHurtMarked;
            player.getCooldowns().removeCooldown(ModItems.HEADPHONES.get());
            player.getCooldowns().removeCooldown(ModItems.BIRTHDAY_CANDLE.get());

            bob.setItemInHand(InteractionHand.MAIN_HAND, bobMain);
            bob.setItemInHand(InteractionHand.OFF_HAND, bobOff);
            bob.removeAllEffects();
            for (net.minecraft.world.effect.MobEffectInstance effect : bobEffects) bob.addEffect(effect);
            bob.setHealth(Math.min(bobHealth, bob.getMaxHealth()));
            bob.setDeltaMovement(bobVelocity);
            bob.hurtMarked = bobHurtMarked;
            bob.containerMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }
    }

    /** 必须经由 ItemStack 查询，以真实触发 Forge 的 ItemAttributeModifierEvent。 */
    private static double stackAttributeTotal(ItemStack stack, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return stackAttributeTotal(stack, net.minecraft.world.entity.EquipmentSlot.MAINHAND, attribute);
    }

    private static double stackAttributeTotal(ItemStack stack, net.minecraft.world.entity.EquipmentSlot slot,
                                              net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return stack.getAttributeModifiers(slot)
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
        // 玩家从南侧朝北进行真实 POV Clip。清出完整射线路径；命中方块南侧才是原版倒水落点。
        net.minecraft.core.BlockPos pourPos = fluidPos.south();
        net.minecraft.core.BlockPos rayPos2 = fluidPos.south(2);
        net.minecraft.core.BlockPos rayPos3 = fluidPos.south(3);
        // 岩浆夹具与真实倒水区域分离，避免原版相邻水/岩浆反应篡改待测源方块。
        net.minecraft.core.BlockPos lavaPos = fluidPos.east(4);
        net.minecraft.core.BlockPos lavaRay1 = lavaPos.south();
        net.minecraft.core.BlockPos lavaRay2 = lavaPos.south(2);
        net.minecraft.core.BlockPos lavaRay3 = lavaPos.south(3);
        net.minecraft.core.BlockPos cheerPos = fluidPos.east(2);
        net.minecraft.core.BlockPos cheerSupport = cheerPos.below();
        net.minecraft.world.level.block.state.BlockState oldFluid = level.getBlockState(fluidPos);
        net.minecraft.world.level.block.state.BlockState oldPour = level.getBlockState(pourPos);
        net.minecraft.world.level.block.state.BlockState oldRay2 = level.getBlockState(rayPos2);
        net.minecraft.world.level.block.state.BlockState oldRay3 = level.getBlockState(rayPos3);
        net.minecraft.world.level.block.state.BlockState oldLava = level.getBlockState(lavaPos);
        net.minecraft.world.level.block.state.BlockState oldLavaRay1 = level.getBlockState(lavaRay1);
        net.minecraft.world.level.block.state.BlockState oldLavaRay2 = level.getBlockState(lavaRay2);
        net.minecraft.world.level.block.state.BlockState oldLavaRay3 = level.getBlockState(lavaRay3);
        net.minecraft.world.level.block.state.BlockState oldCheer = level.getBlockState(cheerPos);
        net.minecraft.world.level.block.state.BlockState oldCheerSupport = level.getBlockState(cheerSupport);
        double oldX = player.getX(), oldY = player.getY(), oldZ = player.getZ();
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            // 眼高落在水源方块内部，朝正北的真实射线必经 fluidPos。
            player.teleportTo(level, fluidPos.getX() + 0.5D, fluidPos.getY() - 1.0D, fluidPos.getZ() + 3.5D,
                    180.0F, 0.0F);

            ItemStack bath = new ItemStack(ModItems.BATH_BUCKET.get());
            if (bath.getMaxDamage() != 10) throw new IllegalStateException("bath bucket durability is not ten");
            player.setItemInHand(InteractionHand.MAIN_HAND, bath);
            level.setBlock(pourPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(rayPos2, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(rayPos3, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            if (!level.getBlockState(fluidPos).getFluidState().isSource()
                    || !bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.WATER) {
                throw new IllegalStateException("bath bucket water fixture failed: source="
                        + level.getBlockState(fluidPos).getFluidState().isSource() + ", contained="
                        + RestrictedFluidContainerItem.getContainedFluid(bath));
            }

            level.setBlock(fluidPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            if (!bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.EMPTY) {
                throw new IllegalStateException("bath bucket did not use vanilla-safe emptying semantics");
            }

            player.teleportTo(level, lavaPos.getX() + 0.5D, lavaPos.getY() - 1.0D, lavaPos.getZ() + 3.5D,
                    180.0F, 0.0F);
            level.setBlock(lavaRay1, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(lavaRay2, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(lavaRay3, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(lavaPos, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
            if (!bath.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || bath.getDamageValue() != 1
                    || RestrictedFluidContainerItem.getContainedFluid(bath) != net.minecraft.world.level.material.Fluids.LAVA) {
                throw new IllegalStateException("bath bucket lava pickup lost durability or container state");
            }

            ItemStack cup = new ItemStack(ModItems.PAPER_CUP.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, cup);
            level.setBlock(lavaPos, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
            if (cup.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult().consumesAction()
                    || RestrictedFluidContainerItem.getContainedFluid(cup) != net.minecraft.world.level.material.Fluids.EMPTY) {
                throw new IllegalStateException("paper cup accepted lava");
            }
            level.setBlock(lavaPos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
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
            level.setBlock(rayPos2, oldRay2, 3);
            level.setBlock(rayPos3, oldRay3, 3);
            level.setBlock(lavaPos, oldLava, 3);
            level.setBlock(lavaRay1, oldLavaRay1, 3);
            level.setBlock(lavaRay2, oldLavaRay2, 3);
            level.setBlock(lavaRay3, oldLavaRay3, 3);
            level.setBlock(cheerPos, oldCheer, 3);
            level.setBlock(cheerSupport, oldCheerSupport, 3);
            player.teleportTo(level, oldX, oldY, oldZ, oldYaw, oldPitch);
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
