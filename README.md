# Shattered Pixel Dungeon

This branch develops the `spdctl` machine-control interface. The current contract
is **CLI.9.0.0 / protocol 9 / audit schema 12**, with base game **4.0.2**.
Start with the [CLI interface](docs/cli.md) and bundled [English help](docs/cli-help.md).
The [current architecture](docs/cli-architecture.md) describes source ownership,
observation boundaries and verification entrypoints; [semantic combat observations](docs/cli-combat-visuals.md)
define the public gameplay facts and source-scoped public world cues.
The [CLI.9.0.0 validation report](docs/cli-9.0.0-v4.0.2-validation.md) records this
batch's evidence, and the [8.0.1 recovery report](docs/cli-8.0.1-recovery-validation.md)
retains the preceding controller and warning fixes. Earlier implementation,
rebuild and acceptance reports remain available in Git history.

For AI-controlled playthroughs, read the [project-local memory](memory/README.md)
and [gameplay skill](skills/pixel-dungeon-run-assistance/SKILL.md). They include
portable read-only public-map/cell/ballistics helpers, versioned Warrior notes,
and the completed-run retrospective. Requested CLI diagnosis has a separate
[maintenance skill](skills/shattered-pixel-dungeon-cli-release/SKILL.md).
The [import record](memory/IMPORTS.md) identifies copied sources and historical
limits; these resources do not require the original machine's personal memory.

[Shattered Pixel Dungeon](https://shatteredpixel.com/shatteredpd/) is an open-source traditional roguelike dungeon crawler with randomized levels and enemies, and hundreds of items to collect and use. It's based on the [source code of Pixel Dungeon](https://github.com/00-Evan/pixel-dungeon-gradle), by [Watabou](https://watabou.itch.io/).

Shattered Pixel Dungeon currently compiles for Android, iOS, and Desktop platforms. You can find official releases of the game on:

[![Get it on Google Play](https://shatteredpixel.com/assets/images/badges/gplay.png)](https://play.google.com/store/apps/details?id=com.shatteredpixel.shatteredpixeldungeon)
[![Download on the App Store](https://shatteredpixel.com/assets/images/badges/appstore.png)](https://apps.apple.com/app/shattered-pixel-dungeon/id1563121109)
[![Steam](https://shatteredpixel.com/assets/images/badges/steam.png)](https://store.steampowered.com/app/1769170/Shattered_Pixel_Dungeon/)<br>
[![GOG.com](https://shatteredpixel.com/assets/images/badges/gog.png)](https://www.gog.com/game/shattered_pixel_dungeon)
[![Itch.io](https://shatteredpixel.com/assets/images/badges/itch.png)](https://shattered-pixel.itch.io/shattered-pixel-dungeon)
[![Github Releases](https://shatteredpixel.com/assets/images/badges/github.png)](https://github.com/00-Evan/shattered-pixel-dungeon/releases)

If you like this game, please consider [supporting me on Patreon](https://www.patreon.com/ShatteredPixel)!

There is an official blog for this project at [ShatteredPixel.com](https://www.shatteredpixel.com/blog/).

The game also has a translation project hosted on [Transifex](https://explore.transifex.com/shattered-pixel/shattered-pixel-dungeon/).

Note that **this repository does not accept pull requests!** The code here is provided in hopes that others may find it useful for their own projects, not to allow community contribution. Issue reports of all kinds (bug reports, feature requests, etc.) are welcome.

If you'd like to work with the code, you can find the following guides in `/docs`:
- [Compiling for Android.](docs/getting-started-android.md)
    - **[If you plan to distribute on Google Play please read the end of this guide.](docs/getting-started-android.md#distributing-your-app)**
- [Compiling for desktop platforms.](docs/getting-started-desktop.md)
- [Compiling for iOS.](docs/getting-started-ios.md)
- [Recommended changes for making your own version.](docs/recommended-changes.md)
