package net.psunset.translatorpp.yomitan.local;

import java.util.List;

/** What the manager screen shows for one dictionary. */
public record DictionaryInfoView(String id, String title, String details, boolean enabled) {

    public static List<DictionaryInfoView> of(LocalYomitan engine) {
        return engine.dictionaries().stream().map(info -> new DictionaryInfoView(info.id(), info.title(),
                String.format("%,d terms · %,d MB", info.terms(), info.bytes() / 1_000_000), info.enabled())).toList();
    }
}
