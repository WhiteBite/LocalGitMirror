package localgitmirror.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLineCodecTest {

  // A connection-profile snapshot. NOTE: as of the scope refactor the config
  // line carries ONLY connection settings — no `repo` (per-project, derived
  // from the git remote) and no `gitRemoteName` (auto-detected).

  @Test
  fun `encode then decode roundtrip preserves values`() {
    val snapshot = ConfigSnapshot(
      baseUrl = "https://127.0.0.1:443",
      mirrorInsecureTls = true,
      offlineGenerateOnly = false,
      pullBackDefaultMode = "new-branch",
      mirrorApiKey = "api-key",
      syncPassword = "dandan"
    )

    val token = ConfigLineCodec.encode(snapshot)
    val decoded = ConfigLineCodec.decode(token)

    assertNotNull(decoded)
    assertEquals(snapshot, decoded)
  }

  @Test
  fun `encoded token starts with V3 prefix`() {
    val snapshot = ConfigSnapshot(
      baseUrl = "https://127.0.0.1",
      mirrorInsecureTls = false,
      offlineGenerateOnly = false,
      pullBackDefaultMode = "new-branch",
      mirrorApiKey = "",
      syncPassword = "s3cr3t"
    )
    val token = ConfigLineCodec.encode(snapshot)
    assertTrue(token.startsWith(ConfigLineCodec.PREFIX), "Expected V3 prefix, got: ${token.take(20)}")
  }

  @Test
  fun `extract token works from noisy clipboard text`() {
    // V1 legacy token with old fields (incl. repo/gitRemoteName) — must still
    // parse the surviving connection fields; obsolete keys are ignored.
    val token = "LGM_CONFIG_V1:YmFzZVVybD1odHRwczovL2EKcmVwbz1yCm1pcnJvckluc2VjdXJlVGxzPXRydWUKb2ZmbGluZUdlbmVyYXRlT25seT10cnVlCmdpdExhYkJhc2VVcmw9CmdpdExhYlByb2plY3Q9CmdpdExhYkluc2VjdXJlVGxzPWZhbHNlCmdpdFJlbW90ZU5hbWU9b3JpZ2luCnB1bGxCYWNrRGVmYXVsdE1vZGU9bmV3LWJyYW5jaAptaXJyb3JBcGlLZXk9CnN5bmNQYXNzd29yZD0KZ2l0TGFiVG9rZW49"
    val noisy = "some prefix\n$token\ntrailing text"
    val extracted = ConfigLineCodec.extractToken(noisy)

    assertNotNull(extracted)
    assertEquals(token, extracted)
  }

  @Test
  fun `decode returns null for invalid payload`() {
    assertNull(ConfigLineCodec.decode("LGM_CONFIG_V1:not-base64***"))
    assertNull(ConfigLineCodec.decode("wrong-prefix:abc"))
  }

  @Test
  fun `decode applies defaults for empty optional fields and ignores obsolete keys`() {
    val raw = """
      baseUrl=https://x
      repo=repo1
      mirrorInsecureTls=true
      offlineGenerateOnly=true
      gitRemoteName=
      pullBackDefaultMode=
      mirrorApiKey=
      syncPassword=
    """.trimIndent()

    val token = "LGM_CONFIG_V1:" + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
    val decoded = ConfigLineCodec.decode(token)

    assertNotNull(decoded)
    assertEquals("https://x", decoded.baseUrl)
    assertEquals("new-branch", decoded.pullBackDefaultMode)
    assertEquals(true, decoded.offlineGenerateOnly)
  }

  @Test
  fun `backward compat - old V config with repo and gitLab fields parses without crash`() {
    // A pre-refactor payload that contains repo / gitRemoteName / gitLab fields.
    // All obsolete keys must be silently ignored; the connection fields parse.
    val rawOldConfig = """
      baseUrl=https://192.168.1.50:443
      repo=my-project
      mirrorInsecureTls=true
      offlineGenerateOnly=false
      simpleUiMode=false
      gitLabBaseUrl=https://gitlab.example.local
      gitLabProject=group/project
      gitLabInsecureTls=false
      gitRemoteName=origin
      pullBackDefaultMode=new-branch
      mirrorApiKey=some-api-key
      syncPassword=secret123
      gitLabToken=glpat-xxxxxxxxxxxxxxxxxxxx
      workMode=auto
    """.trimIndent()

    val decoded = ConfigLineCodec.decode(rawOldConfig)

    assertNotNull(decoded, "Old config with obsolete fields must parse without returning null")
    assertEquals("https://192.168.1.50:443", decoded.baseUrl)
    assertEquals(true, decoded.mirrorInsecureTls)
    assertEquals("some-api-key", decoded.mirrorApiKey)
    assertEquals("secret123", decoded.syncPassword)
    // repo, gitRemoteName, gitLab* are no longer part of ConfigSnapshot — ignored.
  }

  @Test
  fun `extract token supports markdown and case-insensitive prefix`() {
    val snapshot = ConfigSnapshot(
      baseUrl = "https://192.168.0.104:443",
      mirrorInsecureTls = true,
      offlineGenerateOnly = false,
      pullBackDefaultMode = "new-branch",
      mirrorApiKey = "k",
      syncPassword = "p"
    )

    val token = ConfigLineCodec.encode(snapshot)
    val lower = token.replace("LGM_CONFIG_V3:", "lgm_config_v3:")
    val noisy = """
      Some intro text
      ```text
      $lower
      ```
      tail
    """.trimIndent()

    val extracted = ConfigLineCodec.extractToken(noisy)
    assertNotNull(extracted)
    assertTrue(extracted.startsWith(ConfigLineCodec.PREFIX))

    val decoded = ConfigLineCodec.decode(extracted)
    assertNotNull(decoded)
    assertEquals(snapshot.baseUrl, decoded.baseUrl)
    assertEquals(snapshot.mirrorApiKey, decoded.mirrorApiKey)
  }

  @Test
  fun `decode supports direct key value payload`() {
    val raw = """
      baseUrl=https://192.168.0.104:443
      repo=default
      mirrorInsecureTls=true
      offlineGenerateOnly=false
      gitRemoteName=origin
      pullBackDefaultMode=new-branch
      mirrorApiKey=abc
      syncPassword=xyz
    """.trimIndent()

    val decoded = ConfigLineCodec.decode(raw)
    assertNotNull(decoded)
    assertEquals("https://192.168.0.104:443", decoded.baseUrl)
    assertEquals("abc", decoded.mirrorApiKey)
    assertEquals("xyz", decoded.syncPassword)
  }

  @Test
  fun `extractOrNull handles ansi colored text around prefix`() {
    val token = ConfigLineCodec.encode(
      ConfigSnapshot(
        baseUrl = "https://x",
        mirrorInsecureTls = true,
        offlineGenerateOnly = false,
        pullBackDefaultMode = "new-branch",
        mirrorApiKey = "k",
        syncPassword = "p"
      )
    )

    val noisy = "\u001B[32m${token.substring(0, 16)}\u001B[0m${token.substring(16)}"
    val extracted = ConfigLineCodec.extractOrNull(noisy)
    assertNotNull(extracted)
    val decoded = ConfigLineCodec.decode(extracted)
    assertNotNull(decoded)
    assertEquals("https://x", decoded.baseUrl)
  }

  @Test
  fun `extractOrNull can recover from payload-only text`() {
    val token = ConfigLineCodec.encode(
      ConfigSnapshot(
        baseUrl = "https://payload-only",
        mirrorInsecureTls = true,
        offlineGenerateOnly = false,
        pullBackDefaultMode = "new-branch",
        mirrorApiKey = "k",
        syncPassword = "p"
      )
    )
    val payloadOnly = token.replaceFirst(":", "=")
    val extracted = ConfigLineCodec.extractOrNull(payloadOnly)
    assertNotNull(extracted)
    val decoded = ConfigLineCodec.decode(extracted)
    assertNotNull(decoded)
    assertEquals("https://payload-only", decoded.baseUrl)
  }
}
