# Android runtime dependency and license inventory

Generated 2026-10-28 from the resolved `:app:releaseRuntimeClasspath` and bundled player-runtime assets. The machine-readable component list is in [`android-runtime-dependency-licenses.csv`](android-runtime-dependency-licenses.csv).

## Scope and result

- Gradle command: `:app:dependencies --configuration releaseRuntimeClasspath --console=plain`.
- Resolved Android runtime components: 157.
- Bundled player-runtime JavaScript packages: 4.
- The `quickjs-kt-android` AAR includes `libquickjs.so`; the upstream QuickJS C engine is recorded separately from the Apache-2.0 Kotlin wrapper.
- Combined inventory rows: 162 (157 Apache-2.0, 4 MIT, 1 BSD-3-Clause, 0 unknown).
- Android runtime components: 156 Apache-2.0 and one BSD-3-Clause component. The three Google Guava artifacts have no license element in the published artifact POM; their license is confirmed by the official Google Guava project POM.
- The four bundled player-runtime packages comprise three MIT packages and one Apache-2.0 package. QuickJS C engine is separately MIT licensed.
- No GPL-licensed components were found in the resolved Android runtime graph or listed player-runtime assets. This is a dependency metadata inventory, not a legal opinion or a source-file-level audit of every packaged binary.

## Primary license sources

- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- BSD-3-Clause: https://opensource.org/license/bsd-3-clause
- MIT for QuickJS: https://bellard.org/quickjs/
- Guava license metadata: https://github.com/google/guava/blob/master/pom.xml
- quickjs-kt Android wrapper and artifact: https://github.com/dokar3/quickjs-kt

## Distribution notice status

The About screen loads `app/src/main/assets/licenses/third-party-notices.txt`. It contains the 162-row runtime inventory, the existing full notices for the four JavaScript player-runtime packages, the QuickJS C engine MIT notice, the BSD-3-Clause protobuf notice, and the Apache-2.0 license text used by the Apache components. The release About dialog presents this file in a scrollable notice view.

This inventory was generated from the resolved release runtime graph and asset package notices. Re-run the Gradle command and regenerate the CSV whenever runtime dependencies or bundled player-runtime assets change.

