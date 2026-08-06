package cn.blindboxchallenge.item;

import net.minecraft.world.item.FlintAndSteelItem;

/** 024：完整沿用原版打火石交互和 64 点耐久。 */
public final class LighterItem extends FlintAndSteelItem {
    public LighterItem() {
        super(new Properties().durability(64));
    }
}
