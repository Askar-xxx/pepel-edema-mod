package com.pepel.edema.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BookNotificationsProvider implements ICapabilitySerializable<CompoundTag>
{
    public static final Capability<BookNotificationsCap> CAP =
            CapabilityManager.get(new CapabilityToken<>(){});

    private final BookNotificationsCap instance = new BookNotificationsCap();
    private final LazyOptional<BookNotificationsCap> lazy = LazyOptional.of(() -> instance);

    public static void register(RegisterCapabilitiesEvent event)
    {
        event.register(BookNotificationsCap.class);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side)
    {
        return cap == CAP ? lazy.cast() : LazyOptional.empty();
    }

    public void invalidate()
    {
        lazy.invalidate();
    }

    @Override
    public CompoundTag serializeNBT()
    {
        return instance.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt)
    {
        instance.deserializeNBT(nbt);
    }
}
