# Get the jar (pick one)

## A. On your PC (needs JDK 21 only; Gradle downloads itself)
    Windows:   gradlew.bat build
    Mac/Linux: ./gradlew build
The mod jar appears in `build/libs/godzilla_mod-1.0.0.jar`. First build takes a few minutes (downloads Minecraft + NeoForge).
Test it in-game without installing: `gradlew runClient`, then `/summon godzilla_mod:godzilla_minus_one`.

## B. No setup at all: let GitHub build it
1. Create a new PRIVATE GitHub repo and upload everything in this folder (keep the hidden `.github` folder).
2. Open the repo's **Actions** tab -> the "Build" run -> download the **godzilla_mod-jar** artifact (a zip containing the jar).
   (If Actions shows "Get started" instead, use "set up a workflow yourself" and paste the workflow from the earlier chat message.)

## Installing the jar
Minecraft 1.21.1 + NeoForge 21.1.x. **No other mods are needed any more** (GeckoLib is gone). Put the jar in the `mods` folder, on both client and server.

See MOD_README.md for how it works. Keep your GitHub repository PRIVATE: the model data is derived from a third-party Sketchfab model (see CREDITS.md).
This code has not been compiled yet: if the first build fails, send the lines containing `error:`.
