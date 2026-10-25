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

- **Look up any text**: with any screen open, hold the lookup key (Left Shift, rebindable as "Yomitan Lookup")
  over text: container titles, item names, book pages, chat, buttons, the scoreboard and other HUD text. The
  mod records every line of text the GUI draws each frame, so it works without knowing which screen drew it.
  The popup lists every result from every dictionary, styled like Yomitan's (furigana, tag pills, numbered
  senses); scroll it with the mouse wheel. The matched text is highlighted.
- **Pin**: Shift + middle click (rebindable as "Pin Text for Dictionary Lookup").
  - With a tooltip showing (items, buttons, chat hover text): freezes it in place so you can hover it.
  - During play: opens a lookup screen. Aimed at a sign, text display or nametag, that text is pinned (text
    behind blocks is skipped unless the game draws it through walls; stacked hologram lines are joined).
    Otherwise the screen is transparent and just frees the cursor, so the HUD can be hovered.
- **Free cursor**: Left Alt (rebindable as "Free Cursor for Lookups") toggles a free cursor during play without
  moving the camera, to hover the action bar, boss bars, scoreboard and other HUD text. Press it again or Esc to close.
- Drag the box by its header. Left-click drag over its text to select, then Ctrl+C to copy.
  Any click outside the popup closes it; Esc closes the popup, then the box; the pin key over the box also closes it.
- The popup's width and max height are in the settings screen (O), under Yomitan.
- Translation keeps its own key (T). It is ignored while you are typing in a text box, and the default
  target language is English.

Requires Fabric API and Cloth Config.

Text that is drawn as an image (map art, custom font icons) can't be read.

---

# Translator++

A Minecraft mod for swift, light, and convenient auto translation.

## Downloads

You can download this mod on:
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/translatorpp)
- [Modrinth](https://modrinth.com/mod/translatorpp)
