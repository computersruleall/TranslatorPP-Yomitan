# Translator++ (Yomitan fork)

A fork of [Translator++](https://github.com/pSUNSET/TranslatorPP) for Minecraft 26.3 (Fabric only, for Lunar Client).
It translates with Microsoft Azure using your own key, and has a built-in Yomitan-style dictionary. The mod has no commands.

Requires Fabric API and Cloth Config.

## Translation

Add your key from an Azure Translator resource in the settings (O), plus its region unless the resource is global.
The key is stored unencrypted in `config/translatorpp.toml`.

Chat Translation has two modes:

- **Hover Key**: nothing happens until you hold the translate key (T) over a message, item or book.
- **Automatic**: messages not in your target language are replaced by their translation as they arrive, keeping the
  rank, name and icons around them. Hover the translated text for the original. The hover key still works everywhere.
  Your own messages are left alone. Language detection comes first, so English chat costs no translation characters.

**Treat Chat as Japanese On** lists server addresses, separated by commas. On those servers all chat goes to Azure as
Japanese with no detection, in both modes, and Latin text is read as romaji. English written there will come out wrong.

## Dictionary lookups

Lookups run inside the game, from dictionaries you import. No browser is needed.

- **Manage dictionaries**: Y opens the manager (rebindable). Drop Yomitan dictionary `.zip` files onto the window, or put
  them in `config/translatorpp/import` and press Import from Folder. Reorder, turn on and off, or remove them there.
  The top dictionary's entries come first when several match equally well. Term dictionaries only for now: kanji,
  frequency and pitch-accent dictionaries, and images in entries, aren't supported.
- **Look up any text**: with any screen open, hold the lookup key (Left Shift, rebindable) over text: container titles,
  item names, book pages, chat, buttons, the scoreboard and other HUD text. A word wrapped onto the next line is still
  found. The popup lists every result from every enabled dictionary; scroll it with the mouse wheel.
- **Pin**: Shift + middle click (rebindable) freezes a visible tooltip so you can hover it. During play it opens a lookup
  screen with the sign, text display or nametag you're aiming at.
- **Free cursor**: Left Alt (rebindable) frees the cursor during play without moving the camera, to hover HUD text.
  Press it again, left-click empty space, or press Esc to close.
- In the pinned box, drag the header to move it, or drag over text to select it and press Ctrl+C to copy. Any click
  outside the popup closes it. Esc closes the popup, then the box.
- Popup size is in the settings under Yomitan.

Text drawn as an image (map art, custom font icons) can't be read.

Lookup Source in the settings can switch back to the Yomitan browser extension through
[yomitan-api](https://github.com/yomidevs/yomitan-api), which needs the browser running.

---

# Translator++

A Minecraft mod for swift, light, and convenient auto translation.

## Downloads

You can download this mod on:
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/translatorpp)
- [Modrinth](https://modrinth.com/mod/translatorpp)
