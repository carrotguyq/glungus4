# EMC Fall Finder (Fabric 1.21.11)

Client-side mod. Run `/emcfall help` in game. First use scans the public EarthMC API
(a few minutes), then results are cached for an hour.

Example: `/emcfall 12h open falls gold:500`

## Build the jar on GitHub

Push this repo. The **Build mod** workflow runs automatically. Open the run in the
Actions tab and download the `emcfall-jar` artifact (a zip containing the jar).
To get a direct jar download, push a tag such as `v1.0.0` and grab it from Releases.

## Install

Fabric Loader for 1.21.11 + Fabric API, then put the jar in `.minecraft/mods`.
