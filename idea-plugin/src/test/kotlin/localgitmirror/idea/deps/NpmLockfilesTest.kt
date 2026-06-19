package localgitmirror.idea.deps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for pnpm-lock.yaml and yarn.lock parsing in [NpmEcosystem].
 *
 * Same "corporate = non-public registry" contract as package-lock.json, and the
 * same DepCoordinate(ecosystem="npm", group=scope, name, version, classifier=integrity)
 * output shape. These tests are written to catch real parsing bugs: wrong block
 * boundaries, scope mis-splits, and public/corporate inversion.
 */
class NpmLockfilesTest {

  // ── pnpm ─────────────────────────────────────────────────────────────────────

  @Test
  fun `pnpm picks only corporate tarball packages`() {
    val yaml = """
    lockfileVersion: '6.0'

    dependencies:
      '@corp/ui-kit':
        specifier: ^2.3.0
        version: 2.3.1
      lodash:
        specifier: ^4.17.0
        version: 4.17.21

    packages:

      /@corp/ui-kit@2.3.1:
        resolution: {integrity: sha512-CORP==, tarball: https://nexus.corp.local/repository/npm/@corp/ui-kit/-/ui-kit-2.3.1.tgz}
        engines: {node: '>=14'}
        dev: false

      /lodash@4.17.21:
        resolution: {integrity: sha512-PUBLIC==, tarball: https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz}
        dev: false
    """.trimIndent()

    val coords = NpmEcosystem.parsePnpmLockForCorporate(yaml)
    assertEquals(1, coords.size, "Only @corp/ui-kit expected, got ${coords.map { it.label }}")
    val c = coords.single()
    assertEquals("@corp", c.group)
    assertEquals("ui-kit", c.name)
    assertEquals("2.3.1", c.version)
    assertEquals("sha512-CORP==", c.classifier)
    assertEquals("@corp/ui-kit@2.3.1", c.label)
    assertTrue(coords.none { it.name == "lodash" }, "Public lodash must be excluded")
  }

  @Test
  fun `pnpm uses top-level corporate registry when no explicit tarball`() {
    // No tarball field, but registry: points at a corporate host -> corporate.
    val yaml = """
    lockfileVersion: '6.0'
    registry: https://nexus.corp.local/repository/npm/

    packages:

      /@corp/utils@1.0.0:
        resolution: {integrity: sha512-UTILS==}
        dev: false
    """.trimIndent()

    val coords = NpmEcosystem.parsePnpmLockForCorporate(yaml)
    assertEquals(1, coords.size)
    assertEquals("@corp/utils@1.0.0", coords.single().label)
    assertEquals("sha512-UTILS==", coords.single().classifier)
  }

  @Test
  fun `pnpm with public registry and no tarball yields nothing`() {
    // Inverse of the above: default public registry must NOT be flagged corporate.
    val yaml = """
    lockfileVersion: '6.0'
    registry: https://registry.npmjs.org/

    packages:

      /lodash@4.17.21:
        resolution: {integrity: sha512-PUBLIC==}
        dev: false
    """.trimIndent()

    assertTrue(NpmEcosystem.parsePnpmLockForCorporate(yaml).isEmpty())
  }

  @Test
  fun `pnpm skips git and file tarball resolutions`() {
    val yaml = """
    lockfileVersion: '6.0'

    packages:

      /forked@1.0.0:
        resolution: {type: git, integrity: sha512-IGNORED==}
        dev: false

      /local-thing@0.0.0:
        resolution: {tarball: file:../local-thing}
        dev: false

      github.com/x/forked/abc123:
        resolution: {tarball: https://codeload.github.com/x/forked/tar.gz/abc123}
        dev: false
    """.trimIndent()

    assertTrue(
      NpmEcosystem.parsePnpmLockForCorporate(yaml).isEmpty(),
      "git/file/codeload entries are not registry tarballs"
    )
  }

  @Test
  fun `pnpm handles multiline resolution block fields`() {
    // Some pnpm output writes resolution children on their own lines.
    val yaml = """
    lockfileVersion: '6.0'

    packages:

      /@corp/ml@3.1.4:
        resolution:
          integrity: sha512-ML==
          tarball: https://nexus.corp.local/repository/npm/@corp/ml/-/ml-3.1.4.tgz
        dev: false
    """.trimIndent()

    val coords = NpmEcosystem.parsePnpmLockForCorporate(yaml)
    assertEquals(1, coords.size)
    val c = coords.single()
    assertEquals("@corp" to "ml", c.group to c.name)
    assertEquals("3.1.4", c.version)
    assertEquals("sha512-ML==", c.classifier)
  }

  @Test
  fun `pnpm unscoped corporate package splits name correctly`() {
    val yaml = """
    lockfileVersion: '6.0'

    packages:

      /internal-logger@9.9.9:
        resolution: {integrity: sha512-LOG==, tarball: https://nexus.corp.local/repository/npm/internal-logger/-/internal-logger-9.9.9.tgz}
        dev: false
    """.trimIndent()

    val c = NpmEcosystem.parsePnpmLockForCorporate(yaml).single()
    assertEquals("", c.group)
    assertEquals("internal-logger", c.name)
    assertEquals("9.9.9", c.version)
  }

  @Test
  fun `pnpm returns empty for blank input`() {
    assertTrue(NpmEcosystem.parsePnpmLockForCorporate("").isEmpty())
    assertTrue(NpmEcosystem.parsePnpmLockForCorporate("   \n  \n").isEmpty())
  }

  // ── yarn ─────────────────────────────────────────────────────────────────────

  @Test
  fun `yarn picks corporate and skips public`() {
    val text = """
    # THIS IS AN AUTOGENERATED FILE. DO NOT EDIT THIS FILE DIRECTLY.
    # yarn lockfile v1

    "@corp/ui-kit@^2.3.0":
      version "2.3.1"
      resolved "https://nexus.corp.local/repository/npm/@corp/ui-kit/-/ui-kit-2.3.1.tgz#abc123"
      integrity sha512-CORP==

    lodash@^4.17.0:
      version "4.17.21"
      resolved "https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz#def456"
      integrity sha512-PUBLIC==
    """.trimIndent()

    val coords = NpmEcosystem.parseYarnLockForCorporate(text)
    assertEquals(1, coords.size, "Only @corp/ui-kit expected, got ${coords.map { it.label }}")
    val c = coords.single()
    assertEquals("@corp", c.group)
    assertEquals("ui-kit", c.name)
    assertEquals("2.3.1", c.version)
    assertEquals("sha512-CORP==", c.classifier)
    assertTrue(coords.none { it.name == "lodash" }, "Public lodash must be excluded")
  }

  @Test
  fun `yarn scoped package splits scope and name`() {
    val text = """
    # yarn lockfile v1

    "@corp/x@^1.0.0", "@corp/x@~1.2.0":
      version "1.2.3"
      resolved "https://nexus.corp.local/repository/npm/@corp/x/-/x-1.2.3.tgz#aa"
      integrity sha512-X==
    """.trimIndent()

    val c = NpmEcosystem.parseYarnLockForCorporate(text).single()
    assertEquals("@corp", c.group)
    assertEquals("x", c.name)
    assertEquals("1.2.3", c.version)
    assertEquals("sha512-X==", c.classifier)
  }

  @Test
  fun `yarn unscoped package keeps empty scope`() {
    val text = """
    # yarn lockfile v1

    internal-logger@^9.0.0:
      version "9.9.9"
      resolved "https://nexus.corp.local/repository/npm/internal-logger/-/internal-logger-9.9.9.tgz#bb"
      integrity sha512-LOG==
    """.trimIndent()

    val c = NpmEcosystem.parseYarnLockForCorporate(text).single()
    assertEquals("", c.group)
    assertEquals("internal-logger", c.name)
    assertEquals("9.9.9", c.version)
  }

  @Test
  fun `yarn skips git and file resolved entries`() {
    val text = """
    # yarn lockfile v1

    forked@git+https://github.com/x/forked.git:
      version "1.0.0"
      resolved "git+https://github.com/x/forked.git#abc"

    "local@file:../local":
      version "1.0.0"
      resolved "file:../local"
    """.trimIndent()

    assertTrue(NpmEcosystem.parseYarnLockForCorporate(text).isEmpty())
  }

  @Test
  fun `yarn does not bleed fields across block boundaries`() {
    // The public block has no integrity; if block boundaries were wrong, the
    // corporate block's fields could leak into it (or vice versa). This asserts
    // the corporate one is correct and the public one is fully excluded.
    val text = """
    # yarn lockfile v1

    lodash@^4.17.0:
      version "4.17.21"
      resolved "https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz#def456"

    "@corp/ui-kit@^2.3.0":
      version "2.3.1"
      resolved "https://nexus.corp.local/repository/npm/@corp/ui-kit/-/ui-kit-2.3.1.tgz#abc123"
      integrity sha512-CORP==
    """.trimIndent()

    val coords = NpmEcosystem.parseYarnLockForCorporate(text)
    assertEquals(1, coords.size)
    val c = coords.single()
    assertEquals("ui-kit", c.name)
    assertEquals("2.3.1", c.version)
    assertEquals("sha512-CORP==", c.classifier)
  }

  @Test
  fun `yarn returns empty for blank input`() {
    assertTrue(NpmEcosystem.parseYarnLockForCorporate("").isEmpty())
    assertTrue(NpmEcosystem.parseYarnLockForCorporate("# only a comment\n").isEmpty())
  }
}
