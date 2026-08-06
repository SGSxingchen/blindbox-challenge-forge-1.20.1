package cn.blindboxchallenge.event;

import java.util.UUID;
import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.PurpleToyPickaxeSwordItem;
import cn.blindboxchallenge.registry.ModItems;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Item 的默认属性不能按 NBT 区分，故使用 Forge 的 ItemStack 属性事件提供真实主手属性。
 * 此事件在服务端装备更新时决定权威属性；客户端的同一只读调用仅保证提示与服务端一致。
 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModItemAttributeEvents {
    private static final UUID DAMAGE_UUID = UUID.fromString("52116262-a09d-4b67-a19c-0a2cae4b4e02");
    private static final UUID SPEED_UUID = UUID.fromString("f89a08de-415f-4eb0-8e7b-2a0e78d47002");

    @SubscribeEvent
    public static void applyPurpleToyAttributes(ItemAttributeModifierEvent event) {
        if (event.getSlotType() != EquipmentSlot.MAINHAND
                || !event.getItemStack().is(ModItems.PURPLE_TOY_PICKAXE_SWORD.get())) return;

        boolean pickaxeForm = PurpleToyPickaxeSwordItem.isPickaxeForm(event.getItemStack());
        event.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(DAMAGE_UUID, "紫色玩具形态攻击",
                pickaxeForm ? 1.0D : 3.0D, AttributeModifier.Operation.ADDITION));
        event.addModifier(Attributes.ATTACK_SPEED, new AttributeModifier(SPEED_UUID, "紫色玩具形态攻速",
                pickaxeForm ? -2.8D : -2.4D, AttributeModifier.Operation.ADDITION));
    }

    private ModItemAttributeEvents() {}
}
