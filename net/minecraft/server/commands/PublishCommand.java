package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

public class PublishCommand {
   private static final SimpleCommandExceptionType f_138181_ = new SimpleCommandExceptionType(Component.m_237115_("commands.publish.failed"));
   private static final DynamicCommandExceptionType f_138182_ = new DynamicCommandExceptionType((p_138194_) -> {
      return Component.m_237110_("commands.publish.alreadyPublished", p_138194_);
   });

   public static void m_138184_(CommandDispatcher<CommandSourceStack> p_138185_) {
      p_138185_.register(Commands.m_82127_("publish").requires((p_138189_) -> {
         return p_138189_.m_6761_(4);
      }).executes((p_258235_) -> {
         return m_257944_(p_258235_.getSource(), HttpUtil.m_13939_(), false, (GameType)null);
      }).then(Commands.m_82129_("allowCommands", BoolArgumentType.bool()).executes((p_258236_) -> {
         return m_257944_(p_258236_.getSource(), HttpUtil.m_13939_(), BoolArgumentType.getBool(p_258236_, "allowCommands"), (GameType)null);
      }).then(Commands.m_82129_("gamemode", GameModeArgument.m_257772_()).executes((p_258237_) -> {
         return m_257944_(p_258237_.getSource(), HttpUtil.m_13939_(), BoolArgumentType.getBool(p_258237_, "allowCommands"), GameModeArgument.m_257804_(p_258237_, "gamemode"));
      }).then(Commands.m_82129_("port", IntegerArgumentType.integer(0, 65535)).executes((p_258238_) -> {
         return m_257944_(p_258238_.getSource(), IntegerArgumentType.getInteger(p_258238_, "port"), BoolArgumentType.getBool(p_258238_, "allowCommands"), GameModeArgument.m_257804_(p_258238_, "gamemode"));
      })))));
   }

   private static int m_257944_(CommandSourceStack p_260117_, int p_259411_, boolean p_260137_, @Nullable GameType p_259145_) throws CommandSyntaxException {
      if (p_260117_.m_81377_().m_6992_()) {
         throw f_138182_.create(p_260117_.m_81377_().m_7010_());
      } else if (!p_260117_.m_81377_().m_7386_(p_259145_, p_260137_, p_259411_)) {
         throw f_138181_.create();
      } else {
         p_260117_.m_288197_(() -> {
            return m_257556_(p_259411_);
         }, true);
         return p_259411_;
      }
   }

   public static MutableComponent m_257556_(int p_259532_) {
      Component component = ComponentUtils.m_258024_(String.valueOf(p_259532_));
      return Component.m_237110_("commands.publish.started", component);
   }
}
