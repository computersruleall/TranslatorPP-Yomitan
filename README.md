# Translator++ (Yomitan fork)

A fork of [Translator++](https://github.com/pSUNSET/TranslatorPP) for Minecraft 26.3 (Fabric only, for Lunar Client)
that adds Yomitan-style dictionary lookups on top of the original translation features.

## Dictionary lookups

Lookups go to your own [Yomitan](https://github.com/yomidevs/yomitan) through
[yomitan-api](https://github.com/yomidevs/yomitan-api), so they use whatever dictionaries you have installed there.

1. Install yomitan-api (run `install_yomitan_api.py`, pick your browser).
2. In Yomitan's settings, turn on Advanced, then General → **Enable Yomitan API**, and restart the browser.
   The browser must be running while you play; the API listens on `127.0.0.1:19633`.

In game:

- **Pin text**: Shift + middle click (rebindable as "Pin Text for Dictionary Lookup").
  - Over an item in any inventory or menu: pins its tooltip (name, lore, everything shown).
  - Over a chat line with chat open: pins that message.
  - In the world, aimed at a sign, text display or nametag: opens it in a lookup screen.
    Text behind blocks is skipped unless the game draws it through walls. Stacked hologram lines are joined.
- **Look up**: hold Shift and move the cursor over the pinned text. The popup lists every result from every
  dictionary; scroll it with the mouse wheel. The matched text is highlighted.
- Drag the box by its header. Esc closes the popup, then the box; the pin key over the box also closes it.

Text that is drawn as an image (map art, custom font icons) can't be read.

---

# Translator++

A Minecraft mod for swift, light, and convenient auto translation.

## Downloads

You can download this mod on:
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/translatorpp)
- [Modrinth](https://modrinth.com/mod/translatorpp)
