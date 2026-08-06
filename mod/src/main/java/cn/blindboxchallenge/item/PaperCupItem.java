package cn.blindboxchallenge.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** 046-F：纸杯只接受水源，倒水继续沿用原版桶的安全落点判断。 */
public final class PaperCupItem extends RestrictedFluidContainerItem {
    public PaperCupItem() {
        super(new Item.Properties());
    }

    @Override
    protected boolean accepts(Fluid fluid) {
        return fluid == Fluids.WATER;
    }
}
