# Shattered Pixel Dungeon

This branch develops the `spdctl` machine-control interface. The current contract
is **CLI.8.0.0 / protocol 8 / audit schema 11**. Start with the
[CLI interface](docs/cli.md), the bundled [English help](docs/cli-help.md), and
the [CLI8 implementation and validation ledger](docs/cli8-implementation.md).
[Semantic combat observations](docs/cli-combat-visuals.md) describe public
gameplay facts and FOV-scoped world cues. [CLI 7 implementation](docs/cli7-implementation.md),
the [CLI.7.0.1 clean package report](docs/cli-rebuild-7.0.1-clean-20260923.md),
and earlier token studies remain historical evidence for their own versions;
CLI8 package validation is pending.

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
