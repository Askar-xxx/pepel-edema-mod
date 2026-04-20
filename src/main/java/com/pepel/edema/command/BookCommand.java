package com.pepel.edema.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pepel.edema.PepelEdema;
import com.pepel.edema.api.BookNotifications;
import com.pepel.edema.capability.Importance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.command.EnumArgument;

import java.util.Collection;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class BookCommand
{
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(
                Commands.literal("pepel")
                        .then(Commands.literal("book")
                                .then(Commands.literal("notify")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("players", EntityArgument.players())
                                                .then(Commands.argument("entryId", StringArgumentType.string())
                                                        .then(Commands.argument("importance", EnumArgument.enumArgument(Importance.class))
                                                                .executes(BookCommand::execute))))))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
        String entryId = StringArgumentType.getString(ctx, "entryId");
        Importance imp = ctx.getArgument("importance", Importance.class);

        for (ServerPlayer p : targets)
            BookNotifications.notify(p, entryId, imp);

        final int count = targets.size();
        ctx.getSource().sendSuccess(() ->
                Component.literal("Notified " + count + " player(s) · " + entryId), true);
        return count;
    }
}
