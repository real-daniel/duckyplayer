# Third-party dependencies

The build downloads these pinned artifacts from Maven Central:

- JNA 5.19.1 (`jna` and `jna-platform`) — Apache License 2.0 or LGPL 2.1.
  Source: <https://github.com/java-native-access/jna>
- XZ for Java 1.10 — public domain.
  Source: <https://tukaani.org/xz/java.html>
- JLayer 1.0.1 — GNU Lesser General Public License 2.1.
  Source: <http://www.javazoom.net/javalayer/javalayer.html>

The dependency JARs are build inputs and are copied into the packaged
application image. Their expected SHA-256 hashes are recorded in `build.ps1`.
