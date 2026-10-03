# Shared neural counter worker runtime

This module is the counter-family launcher used by Cellpose and StarDist. It keeps
Fiji on its existing Java runtime while a Java 21 worker manages Python. Version
0.1.1 uses windowless Java discovery and worker execution on Windows.

The source snapshot is included so this release builds from a clean checkout.
The authoritative shared module keeps the same package and licence; the plugin
shades it privately to avoid collisions with sibling counters.
