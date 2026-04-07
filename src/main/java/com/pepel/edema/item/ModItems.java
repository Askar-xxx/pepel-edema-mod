package com.pepel.edema.item;

import com.pepel.edema.PepelEdema;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems
{
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, PepelEdema.MODID);

    public static final RegistryObject<Item> BOOK_OF_EREN = ITEMS.register(
            "book_of_eren",
            BookOfErenItem::new
    );
}
