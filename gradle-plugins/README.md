Compose Gradle plugin, thisisthepy fork

The Compose Multiplatform Gradle plugin, published as `org.thisisthepy.compose` so that it can
sit beside JetBrains' `org.jetbrains.compose` without either replacing the other:

* plugin ID: `org.thisisthepy.compose` (marker `org.thisisthepy.compose:org.thisisthepy.compose.gradle.plugin`)
* artifact: `org.thisisthepy.compose:compose-gradle-plugin`
* version: `<upstream version>-ext.<N>`, for example `1.11.1-ext.1`; `-ext.<N>-dev` for a local build

The implementation class and the `compose { }` DSL are JetBrains' unchanged. The library
aliases (`compose.desktop.currentOs`, `compose.material3` and the rest) resolve to
thisisthepy/compose-multiplatform-core-extended, `org.thisisthepy.compose.*`, at the version
in `gradle.properties`; that repository's `extended/COORDINATES.md` lists every coordinate.
The components (`compose.components.resources` and the rest) and Compose HTML are not built
by the fork and still resolve from JetBrains, at the version JetBrains published the same
Compose as.

Environment variables:
* `COMPOSE_GRADLE_PLUGIN_VERSION` - version of plugin
* `COMPOSE_GRADLE_PLUGIN_COMPOSE_VERSION` - version of the Compose libraries the plugin's aliases resolve to
