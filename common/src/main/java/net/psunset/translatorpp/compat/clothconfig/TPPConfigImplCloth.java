package net.psunset.translatorpp.compat.clothconfig;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.api.ComponentizableEnum;
import net.psunset.translatorpp.api.ITPPClothConfigData;
import net.psunset.translatorpp.compat.clothconfig.gui.TPPConfigClothScreenWrapper;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.core.ChatAutoTranslator;
import net.psunset.translatorpp.core.ChatTranslationMode;
import net.psunset.translatorpp.core.TranslationKit;
import net.psunset.translatorpp.core.TranslationMode;
import net.psunset.translatorpp.core.YomitanSource;
import net.psunset.translatorpp.event.ClientTickCallbacks;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import net.psunset.translatorpp.tool.LangUtl;
import org.jetbrains.annotations.ApiStatus;

/**
 * The ClothConfig-compatible implementation of {@link TPPConfig}.
 * To get config values, use {@link TPPConfig#getInstance()}.
 */
@ApiStatus.Internal
public class TPPConfigImplCloth implements TPPConfig {
    private static ConfigHolder<TPPConfigData> holder;

    @Override
    public TranslationMode getMode() {
        return config().mode;
    }

    @Override
    public String getSourceLanguage() {
        return config().source_language;
    }

    @Override
    public String getTargetLanguage() {
        return config().target_language;
    }

    @Override
    public ChatTranslationMode getChatTranslation() {
        return config().chat_translation;
    }

    @Override
    public String getJapaneseServers() {
        return config().japanese_servers;
    }

    @Override
    public String getAzureKey() {
        return config().azure_key;
    }

    @Override
    public String getAzureRegion() {
        return config().azure_region;
    }

    @Override
    public YomitanSource getYomitanSource() {
        return config().yomitan_source;
    }

    @Override
    public int getYomitanPopupWidth() {
        return config().yomitan_popup_width;
    }

    @Override
    public int getYomitanPopupMaxHeight() {
        return config().yomitan_popup_max_height;
    }

    public static TPPConfigData config() {
        return holder.getConfig();
    }

    @Deprecated
    public static ITPPClothConfigData[] configs() {
        return new ITPPClothConfigData[]{config()};
    }

    public static void init() {
        holder = AutoConfig.register(TPPConfigData.class, Toml4jConfigSerializer::new);

        ClientTickCallbacks.POST.register(client -> {
            if (TPPKeyMappings.CONFIG_KEY.isDown()) {
                client.setScreenAndShow(TPPConfigClothScreenWrapper.createScreen(client.gui.screen()));
            }
        });
    }

    @Config(name = TranslatorPP.ID)
    public static final class TPPConfigData implements ITPPClothConfigData {

        /* Translation */
        private TranslationMode mode = Default.mode;
        private String source_language = Default.sourceLanguage;
        private String target_language = Default.targetLanguage;
        private ChatTranslationMode chat_translation = Default.chatTranslation;
        private String japanese_servers = Default.japaneseServers;
        private String azure_key = Default.azureKey;
        private String azure_region = Default.azureRegion;

        /* Yomitan */
        private YomitanSource yomitan_source = Default.yomitanSource;
        private int yomitan_popup_width = Default.yomitanPopupWidth;
        private int yomitan_popup_max_height = Default.yomitanPopupMaxHeight;

        @Override
        public Screen createScreen(Screen parent) {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setSavingRunnable(OnSaveManager::fire)
                    .setTitle(Component.translatable("config.title.translatorpp"));

            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.category.translatorpp.general"));
            ConfigCategory yomitan = builder.getOrCreateCategory(Component.translatable("config.category.translatorpp.yomitan"));

            general.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.translatorpp.chat_translation"), ChatTranslationMode.class, this.chat_translation)
                    .setTooltip(Component.translatable("config.translatorpp.chat_translation.tooltip"))
                    .setEnumNameProvider(e -> ((ComponentizableEnum) e).toComponent())
                    .setDefaultValue(Default.chatTranslation)
                    .setSaveConsumer(it -> this.chat_translation = it)
                    .build());

            general.addEntry(entryBuilder.startStrField(Component.translatable("config.translatorpp.azure_key"), this.azure_key)
                    .setTooltip(Component.translatable("config.translatorpp.azure_key.tooltip"))
                    .setDefaultValue(Default.azureKey)
                    .setSaveConsumer(it -> {
                        if (!this.azure_key.equals(it)) {
                            this.azure_key = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startStrField(Component.translatable("config.translatorpp.azure_region"), this.azure_region)
                    .setTooltip(Component.translatable("config.translatorpp.azure_region.tooltip"))
                    .setDefaultValue(Default.azureRegion)
                    .setSaveConsumer(it -> {
                        if (!this.azure_region.equals(it)) {
                            this.azure_region = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startStrField(Component.translatable("config.translatorpp.japanese_servers"), this.japanese_servers)
                    .setTooltip(Component.translatable("config.translatorpp.japanese_servers.tooltip"))
                    .setDefaultValue(Default.japaneseServers)
                    .setSaveConsumer(it -> {
                        if (!this.japanese_servers.equals(it)) {
                            this.japanese_servers = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.translatorpp.mode"), TranslationMode.class, this.mode)
                    .setTooltip(Component.translatable("config.translatorpp.mode.tooltip"))
                    .setEnumNameProvider(e -> ((ComponentizableEnum) e).toComponent())
                    .setDefaultValue(Default.mode)
                    .setSaveConsumer(it -> {
                        if (this.mode != it) {
                            this.mode = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startStringDropdownMenu(Component.translatable("config.translatorpp.source_language"), this.source_language)
                    .setTooltip(Component.translatable("config.translatorpp.source_language.tooltip"))
                    .setSelections(LangUtl.SOURCE_LANGUAGES)
                    .setDefaultValue(Default.sourceLanguage)
                    .setSaveConsumer(it -> {
                        if (!this.source_language.equals(it)) {
                            this.source_language = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startStringDropdownMenu(Component.translatable("config.translatorpp.target_language"), this.target_language)
                    .setTooltip(Component.translatable("config.translatorpp.target_language.tooltip"))
                    .setSelections(LangUtl.TARGET_LANGUAGES)
                    .setDefaultValue(Default.targetLanguage)
                    .setSaveConsumer(it -> {
                        if (!this.target_language.equals(it)) {
                            this.target_language = it;
                            OnSaveManager.clearTranslationCachesLater();
                        }
                    })
                    .build());

            /* ---------------------------------------- */

            yomitan.addEntry(entryBuilder.startTextDescription(Component.translatable("config.translatorpp.yomitan_manage",
                            TPPKeyMappings.DICTIONARIES_KEY.getTranslatedKeyMessage()))
                    .build());

            yomitan.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.translatorpp.yomitan_source"), YomitanSource.class, this.yomitan_source)
                    .setTooltip(Component.translatable("config.translatorpp.yomitan_source.tooltip"))
                    .setEnumNameProvider(e -> ((ComponentizableEnum) e).toComponent())
                    .setDefaultValue(Default.yomitanSource)
                    .setSaveConsumer(it -> this.yomitan_source = it)
                    .build());

            yomitan.addEntry(entryBuilder.startIntSlider(Component.translatable("config.translatorpp.yomitan_popup_width"), this.yomitan_popup_width, 160, 480)
                    .setTooltip(Component.translatable("config.translatorpp.yomitan_popup_width.tooltip"))
                    .setDefaultValue(Default.yomitanPopupWidth)
                    .setSaveConsumer(it -> this.yomitan_popup_width = it)
                    .build());

            yomitan.addEntry(entryBuilder.startIntSlider(Component.translatable("config.translatorpp.yomitan_popup_max_height"), this.yomitan_popup_max_height, 80, 400)
                    .setTooltip(Component.translatable("config.translatorpp.yomitan_popup_max_height.tooltip"))
                    .setDefaultValue(Default.yomitanPopupMaxHeight)
                    .setSaveConsumer(it -> this.yomitan_popup_max_height = it)
                    .build());

            return builder.build();
        }
    }

    private static final class OnSaveManager {
        private static boolean shouldClearTranslationCaches = false;

        private static void clearTranslationCachesLater() {
            shouldClearTranslationCaches = true;
        }

        private static void fire() {
            holder.save();

            if (shouldClearTranslationCaches) {
                TranslationKit.getInstance().clearCache();
                ChatAutoTranslator.clearCache();
                shouldClearTranslationCaches = false;
            }
        }
    }
}
