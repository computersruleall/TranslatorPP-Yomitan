package net.psunset.translatorpp.config;

import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.annotations.ExpectMixin;
import net.psunset.translatorpp.compat.clothconfig.TPPConfigImplCloth;
import net.psunset.translatorpp.core.ChatTranslationMode;
import net.psunset.translatorpp.core.TranslationMode;
import net.psunset.translatorpp.core.YomitanSource;
import net.psunset.translatorpp.event.ClientTickCallbacks;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import net.psunset.translatorpp.platform.Platform;
import net.psunset.translatorpp.tool.CompatUtl;
import net.psunset.translatorpp.tool.MessageUtl;
import org.jetbrains.annotations.ApiStatus;

/**
 * The main config interface for Translator++.
 * To get config values, use {@link TPPConfig#getInstance()}.
 */
public interface TPPConfig {

    TranslationMode getMode();

    String getSourceLanguage();

    String getTargetLanguage();

    ChatTranslationMode getChatTranslation();

    /** Comma-separated server addresses where all chat is treated as Japanese, romaji included. */
    String getJapaneseServers();

    String getAzureKey();

    String getAzureRegion();

    YomitanSource getYomitanSource();

    /** Width of the Yomitan dictionary popup, in GUI pixels. */
    int getYomitanPopupWidth();

    /** Tallest the Yomitan dictionary popup grows before it scrolls, in GUI pixels. */
    int getYomitanPopupMaxHeight();

    @ExpectMixin(value = ExpectMixin.Expected.NEOFORGE, method = ExpectMixin.Method.OVERWRITE)
    static void init() {
        if (Platform.isNeoForge()) {
            throw new AssertionError("Mixin missing!");
        } else if (CompatUtl.ClothConfig.isLoaded()) {
            TranslatorPP.LOGGER.debug("Cloth Config is loaded, using cloth config for Translator++ Config.");
            Dummy.INSTANCE = new TPPConfigImplCloth();
            TPPConfigImplCloth.init();
        } else {
            TranslatorPP.LOGGER.debug("No config API is loaded, using default values for Translator++ Config.");
            Dummy.INSTANCE = new Dummy();
            Dummy.init();
        }
    }

    static TPPConfig getInstance() {
        return Dummy.INSTANCE;
    }

    /**
     * The dummy implementation of TPPConfig, which uses default values.
     * Only used when no config API is available.
     */
    @ApiStatus.Internal
    class Dummy implements TPPConfig {

        /**
         * The instance of the TPPConfig, not only works for the dummy one.
         * Modify this field is not allowed.
         * To get this instance, use {@link TPPConfig#getInstance()}.
         */
        public static TPPConfig INSTANCE;

        @Override
        public TranslationMode getMode() {
            return Default.mode;
        }

        @Override
        public String getSourceLanguage() {
            return Default.sourceLanguage;
        }

        @Override
        public String getTargetLanguage() {
            return Default.targetLanguage;
        }

        @Override
        public ChatTranslationMode getChatTranslation() {
            return Default.chatTranslation;
        }

        @Override
        public String getJapaneseServers() {
            return Default.japaneseServers;
        }

        @Override
        public String getAzureKey() {
            return Default.azureKey;
        }

        @Override
        public String getAzureRegion() {
            return Default.azureRegion;
        }

        @Override
        public YomitanSource getYomitanSource() {
            return Default.yomitanSource;
        }

        @Override
        public int getYomitanPopupWidth() {
            return Default.yomitanPopupWidth;
        }

        @Override
        public int getYomitanPopupMaxHeight() {
            return Default.yomitanPopupMaxHeight;
        }

        public static void init() {
            ClientTickCallbacks.POST.register(client -> {
                while (TPPKeyMappings.CONFIG_KEY.consumeClick()) {
                    MessageUtl.toLocal(client, Component.translatable("misc.translatorpp.missing.clothconfig"));
                }
            });
        }
    }

    /**
     * To store default values.
     */
    interface Default {
        TranslationMode mode = TranslationMode.NAME_TOP;
        String sourceLanguage = "auto";
        String targetLanguage = "en";
        ChatTranslationMode chatTranslation = ChatTranslationMode.HOVER;
        String japaneseServers = "";
        String azureKey = "";
        String azureRegion = "";
        YomitanSource yomitanSource = YomitanSource.BUILT_IN;
        int yomitanPopupWidth = 230;
        int yomitanPopupMaxHeight = 170;
    }
}