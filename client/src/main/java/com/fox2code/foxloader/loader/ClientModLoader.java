package com.fox2code.foxloader.loader;

import com.fox2code.foxloader.client.network.NetClientHandlerExtensions;
import com.fox2code.foxloader.launcher.BuildConfig;
import com.fox2code.foxloader.launcher.FoxLauncher;
import com.fox2code.foxloader.launcher.LauncherType;
import com.fox2code.foxloader.launcher.utils.IOUtils;
import com.fox2code.foxloader.launcher.utils.NetUtils;
import com.fox2code.foxloader.launcher.utils.Platform;
import com.fox2code.foxloader.launcher.utils.SourceUtil;
import com.fox2code.foxloader.loader.packet.ClientHello;
import com.fox2code.foxloader.loader.packet.ServerHello;
import com.fox2code.foxloader.network.NetworkPlayer;
import com.fox2code.foxloader.network.SidedMetadataAPI;
import com.fox2code.foxloader.registry.GameRegistryClient;
import com.fox2code.foxloader.registry.RegisteredItem;
import com.fox2code.foxloader.updater.UpdateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.fox2code.ChatColors;
import net.minecraft.mitask.PlayerCommandHandler;
import net.minecraft.src.client.gui.StringTranslate;
import net.minecraft.src.client.packets.NetworkManager;
import net.minecraft.src.client.packets.Packet250PluginMessage;
import net.minecraft.src.game.item.Item;
import net.minecraft.src.game.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;

public final class ClientModLoader extends ModLoader {
    public static final boolean linuxFix = Boolean.parseBoolean(
            System.getProperty("foxloader.linux-fix", // Switch to enable linux workaround
                    Boolean.toString(Platform.getPlatform() == Platform.LINUX)));
    private static boolean didPreemptiveNetworking = false;
    public static boolean showFrameTimes;
    private static byte[] clientHello;

    public static void launchModdedClient(String... args) {
        ModLoader.foxLoader.clientMod = new ClientModLoader();
        ModLoader.foxLoader.clientMod.modContainer = ModLoader.foxLoader;
        Objects.requireNonNull(ModLoader.foxLoader.getMod(), "WTF???");
        ModLoader.initializeModdedInstance(true);
        Platform.getPlatform().setupLwjgl2();
        ClientSelfTest.selfTest();
        computeClientHello();
        Minecraft.main(args);
    }

    private static void computeClientHello() {
        final byte[] nullSHA256 = new byte[32];
        try {
            ArrayList<ClientHello.ClientModData> clientModData =
                    new ArrayList<>(ModLoader.modContainers.size() +
                            ModLoader.coreMods.size());
            for (File coreMod : ModLoader.coreMods) {
                byte[] sha256 = IOUtils.sha256Of(coreMod);
                clientModData.add(new ClientHello.ClientModData(
                        coreMod.getName(), sha256, "", ""));
            }
            for (ModContainer modContainer : ModLoader.modContainers.values()) {
                byte[] sha256 = nullSHA256;
                if (modContainer.file != null) {
                    sha256 = IOUtils.sha256Of(modContainer.file);
                }
                clientModData.add(new ClientHello.ClientModData(
                        modContainer.id, sha256,
                        modContainer.name, modContainer.version));
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byteArrayOutputStream.write(0); // <- Packet ID
            new ClientHello(clientModData).writeData(new DataOutputStream(byteArrayOutputStream));
            clientHello = byteArrayOutputStream.toByteArray();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private ClientModLoader() {}

    @Override
    public void onServerStart(NetworkPlayer.ConnectionType connectionType) {
        GameRegistryClient.resetMappings(connectionType.isServer);
    }

    @Override
    public void onReceiveServerPacket(NetworkPlayer networkPlayer, byte[] data) {
        ModLoader.foxLoader.logger.info("Received server packet");
        LoaderNetworkManager.executeServerPacketData(networkPlayer, data);
    }

    @Override
    void loaderHandleServerHello(NetworkPlayer networkPlayer, ServerHello serverHello) {
        ModLoader.foxLoader.logger.info("Initializing id translator");
        GameRegistryClient.initializeMappings(serverHello);
        ModLoader.foxLoader.logger.info("Ids translated!");
        if (!didPreemptiveNetworking) {
            networkPlayer.sendNetworkData(ModLoader.foxLoader, clientHello);
        } else {
            didPreemptiveNetworking = false;
        }
    }

    @Override
    void loaderHandleDoFoxLoaderUpdate(String version, String url) throws IOException {
        File dest = null;
        String[] args;
        LauncherType launcherType = FoxLauncher.getLauncherType();
        getLogger().info("Updating to " + version + " from " + launcherType + " launcher");
        switch (launcherType) {
            default:
                return;
            case BETA_CRAFT:
                File betacraftSource = SourceUtil.getSourceFile(ClientModLoader.class).getParentFile();
                String endPath = ".betacraft/versions";
                String path = betacraftSource.getPath();
                if (!path.replace('\\', '/').endsWith(endPath)) {
                    this.getLogger().warning("Not BetaCraft?");
                    return;
                }
                args = new String[]{null, "-jar", null, "--update", launcherType.name(),
                        path.substring(0, path.length() - endPath.length())};
                break;
            case MMC_LIKE:
                File libraries = ModLoader.foxLoader.file.getParentFile();
                dest = new File(libraries, "foxloader-" + version + ".jar");
            case VANILLA_LIKE:
                args = new String[]{null, "-jar", null, "--update", launcherType.name()};
        }
        if (dest == null) {
            if (!ModLoader.updateTmp.exists() && !ModLoader.updateTmp.mkdirs()) {
                this.getLogger().warning("Unable to create update tmp folder.");
                return;
            }
            dest = new File(ModLoader.updateTmp, "foxloader-" + version + ".jar");
        }
        if (BuildConfig.FOXLOADER_VERSION.equals(version) &&
                FoxLauncher.getLauncherType() != LauncherType.BIN) {
            // Can happen if wrongly installed
            if (!dest.equals(FoxLauncher.foxLoaderFile)) {
                Files.copy(FoxLauncher.foxLoaderFile.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            try (FileOutputStream fileOutputStream = new FileOutputStream(dest)) {
                NetUtils.downloadTo(url, fileOutputStream);
            }
        }
        args[0] = Platform.getPlatform().javaBin.getPath();
        args[2] = dest.getAbsolutePath();
        getLogger().info("Command: " + Arrays.toString(args));
        final Process process = new ProcessBuilder(args).directory(dest.getParentFile()).start();
        if (process.isAlive()) {
            new Thread(() -> {
                Scanner scanner = new Scanner(process.getInputStream());
                String line;
                while (process.isAlive() &&
                        (line = scanner.next()) != null) {
                    System.out.println("Update: " + line);
                    Thread.yield();
                }
                if (!process.isAlive()) {
                    System.out.println("Updated with exit code " + process.exitValue());
                }
            }, "Output log thread");
        } else {
            System.out.println("Updated with exit code " + process.exitValue());
        }
    }

    public static class Internal {
        public static byte[] networkChunkBytes = null;
        private static String serverNameCache;
        private static final HashMap<String, Properties> translationsCache = new HashMap<>();
        private static final Function<String, Properties> translationsCacheFiller = lang -> {
            Properties properties = new Properties();
            for (ModContainer modContainer : ModLoader.modContainers.values()) {
                modContainer.loadLanguageTo(lang, properties);
            }
            return properties;
        };

        static {
            translationsCache.put("en_US", ModLoader.Internal.fallbackTranslations);
        }

        public static Properties getTranslationsForLanguage(String lang) {
            if (!ModLoader.areAllModsLoaded()) return ModLoader.Internal.fallbackTranslations;
            return translationsCache.computeIfAbsent(lang, translationsCacheFiller);
        }

        public static void notifyRun() {
            GameRegistryClient.initialize();
            ModLoader.initializeMods(true);
            UpdateManager.getInstance().initialize();
            GameRegistryClient.freeze();
            ModLoader.postInitializeMods();
            StringTranslate.reloadKeys();
            GameRegistryClient.freezeRecipes();
            // StatList.initBreakableStats();
            // StatList.initStats();
            PlayerCommandHandler.instance.reloadCommands();
            if (ModLoaderOptions.INSTANCE.checkForUpdates) {
                UpdateManager.getInstance().checkUpdates();
            }
            SidedMetadataAPI.Internal.addHandler(
                    () -> Internal.serverNameCache = null);
        }

        public static void notifyCameraAndRenderUpdated(float partialTick) {
            for (ModContainer modContainer : ModLoader.modContainers.values()) {
                modContainer.notifyCameraAndRenderUpdated(partialTick);
            }
        }

        public static void preemptivelySendClientHello(NetworkManager networkManager) {
            if (ModLoaderOptions.INSTANCE.preemptiveNetworking) {
                networkManager.addToSendQueue(new Packet250PluginMessage(
                        ModLoader.foxLoader.id, clientHello));
                didPreemptiveNetworking = true;
            } else {
                didPreemptiveNetworking = false;
            }
        }

        public static void glScaleItem(ItemStack itemStack) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static void glScaleItemNoZFighting(ItemStack itemStack) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                if (scale < 2.001F && scale > 1.999F) scale = 1.999F;
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static void glScaleItemOverXTranslate(ItemStack itemStack, float value) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                GL11.glTranslatef(value - (value * scale), 0, 0);
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static void glScaleItemOverYTranslate(ItemStack itemStack, float value) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                GL11.glTranslatef(0, value - (value * scale), 0);
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static void glScaleItemOverZTranslate(ItemStack itemStack, float value) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                GL11.glTranslatef(0, 0, value - (value * scale));
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static void glScaleItemOverXYTranslate(ItemStack itemStack, float xValue, float yValue) {
            float scale = ClientMod.toRegisteredItemStack(itemStack).getWorldItemScale();
            if (scale > 0F && scale != 1F) {
                GL11.glTranslatef(xValue - (xValue * scale), yValue - (yValue * scale), 0);
                GL11.glScalef(scale, scale, scale);
            }
        }

        public static String getColoredServerNameDebugExt() {
            if (Internal.serverNameCache == null) {
                Map<String, String> metadata = SidedMetadataAPI.getActiveMetadata();
                String serverName = metadata.get(SidedMetadataAPI.KEY_VISIBLE_SERVER_NAME);
                if (serverName == null) {
                    if (metadata.containsKey(SidedMetadataAPI.KEY_FOXLOADER_VERSION)) {
                        serverName = "FoxLoader " + metadata.get(SidedMetadataAPI.KEY_FOXLOADER_VERSION);
                    } else if (((NetClientHandlerExtensions) Minecraft.getInstance().getSendQueue()).isFoxLoader()) {
                        serverName = ChatColors.DARK_RED + "Obsolete FoxLoader" + ChatColors.GRAY;
                    } else {
                        serverName = "ReIndev " + BuildConfig.REINDEV_VERSION;
                    }
                }
                Internal.serverNameCache = ChatColors.GRAY + " (Server: " + serverName + ")";
            }
            return Internal.serverNameCache;
        }
    }
}
