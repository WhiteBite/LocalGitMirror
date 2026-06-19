package localgitmirror.idea.deps

import localgitmirror.idea.deps.NpmEcosystem.PublicAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Honest tests for the npm corporate-classification layer (registry probe +
 * scope override). The network probe is INJECTED so these are pure and
 * deterministic — they verify the decision logic, which is where real bugs
 * live (public/corporate inversion, scope matching, UNKNOWN fallback).
 *
 * Scenario that motivates this: a project with `.npmrc registry=<nexus>` makes
 * EVERY package resolve via nexus, so the lockfile parser returns the whole
 * tree (lodash, vue, @krypto-ui/core...). The probe must keep ONLY the packages
 * that genuinely don't exist on the public npm registry.
 */
class NpmRegistryProbeTest {

  private fun npm(scope: String, name: String, version: String = "1.0.0") =
    DepCoordinate("npm", scope, name, version, classifier = "sha512-x")

  // ── filterCorporate: the core decision ──────────────────────────────────────

  @Test
  fun `public package available on registry is dropped`() {
    val coords = listOf(npm("", "lodash"), npm("", "vue"))
    val result = NpmEcosystem.filterCorporate(coords, emptyList()) { PublicAvailability.AVAILABLE }
    assertTrue(result.isEmpty(), "Packages present on public npm must NOT be shipped: ${result.map { it.name }}")
  }

  @Test
  fun `package absent from registry is kept as corporate`() {
    val coords = listOf(npm("@krypto-ui", "core", "0.30.0"))
    val result = NpmEcosystem.filterCorporate(coords, emptyList()) { PublicAvailability.ABSENT }
    assertEquals(1, result.size)
    assertEquals("core", result[0].name)
    assertEquals("@krypto-ui", result[0].group)
  }

  @Test
  fun `mixed tree keeps only the registry-absent packages`() {
    // The real onyx-platform case: nexus proxies everything, parser returns all,
    // probe must isolate the corporate ones.
    val coords = listOf(
      npm("", "lodash", "4.17.21"),
      npm("@krypto-ui", "core", "0.30.0"),
      npm("", "vue", "3.4.0"),
      npm("@krypto-ui", "components", "2.23.2"),
    )
    val absent = setOf("@krypto-ui/core@0.30.0", "@krypto-ui/components@2.23.2")
    val result = NpmEcosystem.filterCorporate(coords, emptyList()) { c ->
      val full = "${c.group}/${c.name}@${c.version}"
      if (full in absent) PublicAvailability.ABSENT else PublicAvailability.AVAILABLE
    }
    assertEquals(2, result.size, "Only the two @krypto-ui packages are corporate: ${result.map { it.label }}")
    assertTrue(result.all { it.group == "@krypto-ui" })
    assertTrue(result.none { it.name == "lodash" || it.name == "vue" })
  }

  @Test
  fun `unknown probe result is shipped conservatively`() {
    // Network error/timeout → we cannot prove it's public → ship to be safe,
    // so the dome build never fails for lack of a package.
    val coords = listOf(npm("", "mystery"))
    val result = NpmEcosystem.filterCorporate(coords, emptyList()) { PublicAvailability.UNKNOWN }
    assertEquals(1, result.size, "UNKNOWN must be shipped (better over-ship than break the build)")
  }

  // ── scope override ───────────────────────────────────────────────────────────

  @Test
  fun `scope override force-includes even when available on public registry`() {
    // A package that IS on public npm but the user insists it's corporate.
    val coords = listOf(npm("@krypto-ui", "core"))
    val result = NpmEcosystem.filterCorporate(coords, listOf("@krypto-ui")) { PublicAvailability.AVAILABLE }
    assertEquals(1, result.size, "Scope override must force-include despite AVAILABLE")
  }

  @Test
  fun `scope override does not affect non-matching packages`() {
    val coords = listOf(npm("", "lodash"))
    // lodash doesn't match @krypto-ui and is public → still dropped.
    val result = NpmEcosystem.filterCorporate(coords, listOf("@krypto-ui")) { PublicAvailability.AVAILABLE }
    assertTrue(result.isEmpty())
  }

  @Test
  fun `prefix scope matches unscoped corporate cli`() {
    // "krypto-" prefix should match a bare package name "krypto-cli".
    val coords = listOf(npm("", "krypto-cli"))
    val result = NpmEcosystem.filterCorporate(coords, listOf("krypto-")) { PublicAvailability.AVAILABLE }
    assertEquals(1, result.size)
    assertEquals("krypto-cli", result[0].name)
  }

  // ── matchesScope ─────────────────────────────────────────────────────────────

  @Test
  fun `matchesScope matches exact scope`() {
    assertTrue(NpmEcosystem.matchesScope(npm("@krypto-ui", "core"), listOf("@krypto-ui")))
  }

  @Test
  fun `matchesScope matches full name prefix`() {
    assertTrue(NpmEcosystem.matchesScope(npm("@krypto-ui", "core"), listOf("@krypto-ui/core")))
    assertTrue(NpmEcosystem.matchesScope(npm("", "krypto-cli"), listOf("krypto-")))
  }

  @Test
  fun `matchesScope does not match unrelated package`() {
    assertFalse(NpmEcosystem.matchesScope(npm("", "lodash"), listOf("@krypto-ui", "krypto-")))
    assertFalse(NpmEcosystem.matchesScope(npm("@other", "thing"), listOf("@krypto-ui")))
  }

  @Test
  fun `matchesScope with empty list is always false`() {
    assertFalse(NpmEcosystem.matchesScope(npm("@krypto-ui", "core"), emptyList()))
  }

  // ── parseScopes ──────────────────────────────────────────────────────────────

  @Test
  fun `parseScopes splits on comma and newline and trims`() {
    assertEquals(listOf("@krypto-ui", "krypto-"), NpmEcosystem.parseScopes(" @krypto-ui , krypto- "))
    assertEquals(listOf("@a", "@b"), NpmEcosystem.parseScopes("@a\n@b"))
  }

  @Test
  fun `parseScopes on blank yields empty`() {
    assertTrue(NpmEcosystem.parseScopes("").isEmpty())
    assertTrue(NpmEcosystem.parseScopes("  ,  \n , ").isEmpty())
  }

  // ── non-npm coordinates are ignored ──────────────────────────────────────────

  @Test
  fun `filterCorporate ignores non-npm coordinates`() {
    val coords = listOf(DepCoordinate("gradle", "g", "n", "1"), npm("@krypto-ui", "core"))
    val result = NpmEcosystem.filterCorporate(coords, emptyList()) { PublicAvailability.ABSENT }
    assertEquals(1, result.size)
    assertEquals("npm", result[0].ecosystem)
  }
}
