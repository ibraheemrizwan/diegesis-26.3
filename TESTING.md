# Validation scope

Release: 1.0.0-beta.4+26.3.port.1. Recorded environment: Minecraft 26.3, Java 25, Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3.

- 23 server GameTests passed for this project.
- Across the completed suite: 136 server GameTests and 26 isolated kernel checks passed.
- Dedicated-server integration loaded Diegesis, Hands-On Delight, Hoarfrost, Petrichor and Farmer’s Delight Refabricated together.
- Client checks covered the default renderer, resource/data reload, and saved-world reload. Cooking checks included network cutting, recipe selection and persistent board state. Weather checks included snowstorms, rain, puddles, lightning and sun showers.
- JEI and REI integration were tested in separate cooking-suite runs. Other optional-mod combinations are not implied by these results.

The validation record describes the previously tested release JAR. Repository packaging and documentation are maintained separately; the SHA-256 checksum identifies the exact release artifact.

## Reproduce project checks

Use `./gradlew runGameTestServer` for server tests and `./gradlew runClientTest` for the graphical client fixture. Tests use disposable `run-gametest` and `run-clienttest` directories. Minecraft server testing requires acceptance of Mojang’s EULA in the test directory.
