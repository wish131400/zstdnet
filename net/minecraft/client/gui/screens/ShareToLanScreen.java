package net.minecraft.client.gui.screens;

import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.PublishCommand;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ShareToLanScreen extends Screen {
   private static final int f_262314_ = 1024;
   private static final int f_262301_ = 65535;
   private static final Component f_96640_ = Component.m_237115_("selectWorld.allowCommands");
   private static final Component f_96641_ = Component.m_237115_("selectWorld.gameMode");
   private static final Component f_96642_ = Component.m_237115_("lanServer.otherPlayers");
   private static final Component f_257007_ = Component.m_237115_("lanServer.port");
   private static final Component f_257045_ = Component.m_237110_("lanServer.port.unavailable.new", 1024, 65535);
   private static final Component f_256909_ = Component.m_237110_("lanServer.port.invalid.new", 1024, 65535);
   private static final int f_257022_ = 16733525;
   private final Screen f_96643_;
   private GameType f_169427_ = GameType.SURVIVAL;
   private boolean f_96647_;
   private int f_256852_ = HttpUtil.m_13939_();
   @Nullable
   private EditBox f_256803_;

   public ShareToLanScreen(Screen p_96650_) {
      super(Component.m_237115_("lanServer.title"));
      this.f_96643_ = p_96650_;
   }

   protected void m_7856_() {
      IntegratedServer integratedserver = this.f_96541_.m_91092_();
      this.f_169427_ = integratedserver.m_130008_();
      this.f_96647_ = integratedserver.m_129910_().m_5468_();
      this.m_142416_(CycleButton.m_168894_(GameType::m_151500_).m_168961_(GameType.SURVIVAL, GameType.SPECTATOR, GameType.CREATIVE, GameType.ADVENTURE).m_168948_(this.f_169427_).m_168936_(this.f_96543_ / 2 - 155, 100, 150, 20, f_96641_, (p_169429_, p_169430_) -> {
         this.f_169427_ = p_169430_;
      }));
      this.m_142416_(CycleButton.m_168916_(this.f_96647_).m_168936_(this.f_96543_ / 2 + 5, 100, 150, 20, f_96640_, (p_169432_, p_169433_) -> {
         this.f_96647_ = p_169433_;
      }));
      Button button = Button.m_253074_(Component.m_237115_("lanServer.start"), (p_280826_) -> {
         this.f_96541_.m_91152_((Screen)null);
         Component component;
         if (integratedserver.m_7386_(this.f_169427_, this.f_96647_, this.f_256852_)) {
            component = PublishCommand.m_257556_(this.f_256852_);
         } else {
            component = Component.m_237115_("commands.publish.failed");
         }

         this.f_96541_.f_91065_.m_93076_().m_93785_(component);
         this.f_96541_.m_91341_();
      }).m_252987_(this.f_96543_ / 2 - 155, this.f_96544_ - 28, 150, 20).m_253136_();
      this.f_256803_ = new EditBox(this.f_96547_, this.f_96543_ / 2 - 75, 160, 150, 20, Component.m_237115_("lanServer.port"));
      this.f_256803_.m_94151_((p_258130_) -> {
         Component component = this.m_257854_(p_258130_);
         this.f_256803_.m_257771_(Component.m_237113_("" + this.f_256852_).m_130940_(ChatFormatting.DARK_GRAY));
         if (component == null) {
            this.f_256803_.m_94202_(14737632);
            this.f_256803_.m_257544_((Tooltip)null);
            button.f_93623_ = true;
         } else {
            this.f_256803_.m_94202_(16733525);
            this.f_256803_.m_257544_(Tooltip.m_257550_(component));
            button.f_93623_ = false;
         }

      });
      this.f_256803_.m_257771_(Component.m_237113_("" + this.f_256852_).m_130940_(ChatFormatting.DARK_GRAY));
      this.m_142416_(this.f_256803_);
      this.m_142416_(button);
      this.m_142416_(Button.m_253074_(CommonComponents.f_130656_, (p_280824_) -> {
         this.f_96541_.m_91152_(this.f_96643_);
      }).m_252987_(this.f_96543_ / 2 + 5, this.f_96544_ - 28, 150, 20).m_253136_());
   }

   public void m_86600_() {
      super.m_86600_();
      if (this.f_256803_ != null) {
         this.f_256803_.m_94120_();
      }

   }

   @Nullable
   private Component m_257854_(String p_259426_) {
      if (p_259426_.isBlank()) {
         this.f_256852_ = HttpUtil.m_13939_();
         return null;
      } else {
         try {
            this.f_256852_ = Integer.parseInt(p_259426_);
            if (this.f_256852_ >= 1024 && this.f_256852_ <= 65535) {
               return !HttpUtil.m_257796_(this.f_256852_) ? f_257045_ : null;
            } else {
               return f_256909_;
            }
         } catch (NumberFormatException numberformatexception) {
            this.f_256852_ = HttpUtil.m_13939_();
            return f_256909_;
         }
      }
   }

   public void m_88315_(GuiGraphics p_281738_, int p_96653_, int p_96654_, float p_96655_) {
      this.m_280273_(p_281738_);
      p_281738_.m_280653_(this.f_96547_, this.f_96539_, this.f_96543_ / 2, 50, 16777215);
      p_281738_.m_280653_(this.f_96547_, f_96642_, this.f_96543_ / 2, 82, 16777215);
      p_281738_.m_280653_(this.f_96547_, f_257007_, this.f_96543_ / 2, 142, 16777215);
      super.m_88315_(p_281738_, p_96653_, p_96654_, p_96655_);
   }
}
