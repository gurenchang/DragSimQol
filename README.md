# Example Mod

## Mining Bot

The stationary Mining Bot supports `Obsidian`, `Large Amethyst Bud`
(`facing=down, waterlogged=false` only), and `Amethyst Block`.
Use the J key or `/miningbot start`; `/obsidian` remains a compatibility alias.
Route Walker continues to use H and `/routewalker` (with `/mining` as its legacy alias).
While Mining Bot is active its Attack key remains held. `Target Block` chooses what the bot
automatically searches for and aims at; blocks crossed by the held Attack may also be mined.
Optional `Aim Drift` adds a slow, bounded movement within the automatic target's shape.
Mining Bot starts only when at least one selected block is loaded, visible, unobstructed,
and within vanilla interaction reach. While it owns the session, physical mouse movement
is consumed without changing the real player rotation; normal mouse look resumes on stop.

The in-game control panel is mining-first: `Mining Bot`, `Route Walker`, `Combat`,
`Visuals`, and focused `Advanced` sections. Route nodes can be added, selected, removed,
or cleared with confirmation without leaving the GUI. Key bindings remain in Minecraft's
standard Controls screen and are linked from the panel header.

自分のForkでの開発手順は [日本語GitHubガイド](docs/GITHUB_GUIDE.ja.md) を参照してください。

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
