# PandoraRigs

Pandora's six-bone display rig from Surge SMP, as a standalone Paper plugin, for any skin.
Wear one, set one loose as a boss, or stand one up as a statue. Vanilla clients, no mods.

Build: `cd plugin` then `.\gradlew.bat build` (JDK 21) -> `plugin/build/libs/PandoraRigs-0.1.0.jar`.
Runs on Paper 1.21.11 and up (built against the 1.21.11 API, Java 21).

## Commands

| Command | Who | What |
| --- | --- | --- |
| `/rig create <id> <player name \| file.png> [slim\|classic]` | op | Fetch a skin (Mojang, or `plugins/PandoraRigs/skins/`), cut it into six bone models, rebuild the pack, send it |
| `/rig become <rig> [player]` | op | Wear it: your body goes invisible, the rig walks, swings, holds your items, nods when you chat |
| `/rig leave`, `/rig emote <name>`, `/rig scale <size>` | op | Stop wearing it, play an emote (bow, wave, kneel, throne…), resize it |
| `/rig boss <rig> [mob]` | op | An invisible mob (husk by default) with the rig riding it, plus a boss bar. Stats are under `rigs.<id>.boss` in config |
| `/rig statue <rig>`, `/rig clear [radius\|all]` | op | A kneeling statue that watches whoever walks up; remove bosses and statues |
| `/rig list`, `/rig pack`, `/rig reload`, `/rig delete <rig>` | op (list and pack: anyone) | Housekeeping |

Everything is op-only by default. To let players wear rigs, give them `pandorarigs.become`
(non-ops are capped at scale 4; ops at 8).

## The pack

The plugin builds `plugins/PandoraRigs/rigs-pack.zip` itself and serves it on port **8125**. It is sent as a
*second* pack next to any server pack, so it never replaces the surgepack. On a public server, open the port and
set `pack.host` to the address players use (or upload the zip yourself and set `pack.external-url`).
`pack.required` stays false by default: with it on, a bad URL kicks everyone.

Arms are Pandora's slim, three-pixel ones. Classic skins are narrowed on the way in. The generated Pandora
models and skin match surgepack's byte for byte in geometry and pixels.

## License

MIT — see [LICENSE](LICENSE).

## The Maker site (`docs/`)

A static page that does `/rig create`'s skin work in the browser: drop a skin, name the rig, see it walk in 3D,
then download `rigs-pack.zip` (the resource pack) and `skins.zip` (the skins for `plugins/PandoraRigs/skins/`).
Nothing is uploaded; it runs entirely client side (three.js + JSZip from CDNs). A pack built there is the same,
file for file, as one the plugin builds.

Publish it with GitHub Pages: push this folder to a GitHub repo, then Settings -> Pages -> Deploy from a branch ->
`main` / `/docs`. To try it locally, serve the folder (ES modules don't load from `file://`):
`python -m http.server 8130 --directory docs`.

Using a zip from the site on a server:

1. Unzip `skins.zip` into `plugins/PandoraRigs/skins/`, then `/rig create <id> <id>.png` for each rig.
2. Either let the plugin host its own pack (default), or upload `rigs-pack.zip` somewhere public (a GitHub
   release asset works) and set `pack.external-url`. The plugin downloads it to learn its sha1, so run
   `/rig reload` after replacing the file. Or merge its `assets/` folder into your server pack and set
   `pack.send: false`.
