# In-game tests

Starts a real Minecraft 26.1.1 client with the real addon jars and Baritone, opens a singleplayer world and plays through test scenarios by itself. Commands are typed as a player would, Baritone really walks and mines, and a scenario passes only if the items end up in the right place on the server.

Built on Fabric's [client game tests](https://docs.fabricmc.net/26.1.2/develop/automatic-testing). This is a test project, not something players install.

## Running

**Windows:** run `run-tests.bat`. A game window opens by itself and closes at the end. Leave it alone while it runs.

**Linux or GitHub Actions:** run `./run-tests.sh`. Without a display it uses Xvfb. The workflow `.github/workflows/ingame-tests.yml` does this and uploads the results.

You need JDK 25 (or the portable one in `tools/`) and `baritone-api-fabric-1.18.0.jar` in `mc-26.1.1/loadchest/libs`.

To run only some scenarios, set `INGAMETEST_ONLY` to part of their name, e.g. `set INGAMETEST_ONLY=hotbar` on Windows.

## Results

In `build/run/clientGameTest/`:

- `ingametest-report.txt` lists pass, fail or skip for each scenario, with the reason for a failure
- `screenshots/fail-<scenario>.png` shows the game at the moment of a failure
- `logs/latest.log` has the full log; search for `[InGameTest]`

The Gradle task fails if any scenario fails, so the exit code can be used by scripts.

## Adding scenarios

Add a method to `LoadChestScenarios` and list it in `all()`:

```java
private static void hotbarOff(Bot b) {
    BlockPos chest = b.chestAt(8, 0);                   // arena is 49 x 49 around the player
    b.give("inventory.0", "cobblestone", 64);
    b.checkInventorySetup();                            // server commands fail silently
    b.depositTrip("loadchest at 8 101 0");
    b.checkContents("chest", b.chestItems(chest), "cobblestone", 64);
}
```

Every scenario starts from a clean arena, an empty inventory and LoadChest's default settings.

## Testing another addon

1. Add its folder to `addons=` in `gradle.properties`.
2. Add a `<Addon>Scenarios` class like `LoadChestScenarios`, with that addon's mod id. Scenarios of addons that are not loaded are skipped.
3. Add its list in `InGameTests`, and its defaults to `Bot.reset()`.

Addons listed together run in the same game, so that is also how to test them side by side.
