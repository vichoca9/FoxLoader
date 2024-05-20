package com.fox2code.foxloader.client.gui;

import com.fox2code.foxloader.loader.ModContainer;
import com.fox2code.foxloader.loader.ModLoader;
import com.fox2code.foxloader.updater.UpdateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.src.client.gui.*;
import org.lwjgl.Sys;

public class GuiModList extends GuiScreen {
    private final GuiScreen parent;
    private GuiModListContainer modListContainer;
    private GuiSmallButton guiUpdateAll, guiConfigureMod;
    private boolean doSingleUpdate;
    private Object guiScreen;

    public GuiModList(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.modListContainer = new GuiModListContainer(this);
        StringTranslate st = StringTranslate.getInstance();
        this.controlList.add(new GuiSmallButton(0,
                this.width / 2 - 154, this.height - 48,
                st.translateKey("mods.openFolder")));
        this.controlList.add(new GuiSmallButton(1,
                this.width / 2 + 4, this.height - 48,
                st.translateKey("gui.done")));
        this.controlList.add(this.guiUpdateAll = new GuiSmallButton(2,
                this.width / 2 - 154, this.height - 24,
                st.translateKey("mods.updateAllMods")));
        this.controlList.add(this.guiConfigureMod = new GuiSmallButton(3,
                this.width / 2 + 4, this.height - 24,
                st.translateKey("mods.configureMod")));
        this.modListContainer.registerScrollButtons(this.controlList, 4, 5);
        // Update GUI state after init
        this.updateGuiState();
    }

    @Override
    public void drawScreen(int var1, int var2, float deltaTicks) {
        this.modListContainer.drawScreen(var1, var2, deltaTicks);
        super.drawScreen(var1, var2, deltaTicks);
    }

    @Override
    protected void actionPerformed(GuiButton var1) {
        if (var1.id == 0) {
            Sys.openURL("file://" + ModLoader.mods);
        } else if (var1.id == 1) {
            this.mc.displayGuiScreen(this.parent);
        } else if (var1.id == 2) {
            UpdateManager.getInstance().doUpdates();
        } else if (var1.id == 3) {
            if (this.guiScreen instanceof GuiConfigProvider) {
                Minecraft.getInstance().displayGuiScreen(
                        ((GuiConfigProvider) this.guiScreen).provideConfigScreen(this));
            } else if (this.guiScreen instanceof GuiScreen) {
                Minecraft.getInstance().displayGuiScreen((GuiScreen) this.guiScreen);
            } else if (this.doSingleUpdate) {
                UpdateManager.getInstance().doUpdate(
                        this.modListContainer.getSelectedModContainer().id);
            } else {
                this.openModConfigScreen(this.modListContainer.getSelectedModContainer());
            }
        } else {
            this.modListContainer.actionPerformed(var1);
        }
    }

    // If somehow you have a better implementation, go ahead
    private void openModConfigScreen(ModContainer modContainer) {
        Minecraft.getInstance().displayGuiScreen(new GuiModConfig(this, modContainer));
    }

    public FontRenderer getFontRenderer() {
        return this.fontRenderer;
    }

    void updateGuiState() {
        final StringTranslate st = StringTranslate.getInstance();
        final ModContainer modContainer = this.modListContainer.getSelectedModContainer();
        this.guiUpdateAll.enabled = UpdateManager.getInstance().hasUpdates();
        this.doSingleUpdate = false;
        this.guiScreen = null;
        this.guiConfigureMod.displayString = st.translateKey("mods.configureMod");
        if (modContainer.getConfigObject() instanceof GuiScreen ||
                modContainer.getConfigObject() instanceof GuiConfigProvider) {
            this.guiScreen = modContainer.getConfigObject();
            this.guiConfigureMod.enabled = true;
        } else if (modContainer.getConfigObject() != null) {
            this.guiConfigureMod.enabled = true;
        } else if (UpdateManager.getInstance().hasUpdate(modContainer.id)) {
            this.doSingleUpdate = true;
            this.guiConfigureMod.enabled = true;
            this.guiConfigureMod.displayString = st.translateKey("mods.updateMod");
        } else {
            this.guiConfigureMod.enabled = false;
        }
    }
}
