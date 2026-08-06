package cn.blindboxchallenge.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** 025：可装水或岩浆；每次成功装入岩浆消耗一点耐久，共十点。 */
public final class BathBucketItem extends RestrictedFluidContainerItem {
    public BathBucketItem() {
        super(new Item.Properties().durability(10));
    }

    @Override
    protected boolean accepts(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.LAVA;
    }

    @Override
    protected boolean damagesOnPickup(Fluid fluid) {
        return fluid == Fluids.LAVA;
    }
}
