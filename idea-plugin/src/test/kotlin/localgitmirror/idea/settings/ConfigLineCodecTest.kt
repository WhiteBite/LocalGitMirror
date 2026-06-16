package localgitmirror.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLineCodecTest {

  @Test
  fun `encode then decode roundtrip preserves values`() {
    val snapshot = ConfigSnapshot(
      baseUrl = "https://127.0.0.1:443",
      repo = "onyx-platform",
      mirrorInsecureTls = true,
      offlineGenerateOnly = false,
      simpleUiMode = false,
      gitLabBaseUrl = "https://gitlab.example.local",
      gitLabProject = "group/project",
      gitLabInsecureTls = false,
      gitRemoteName = "origin",
      pullBackDefaultMode = "new-branch",
      mirrorApiKey = "api-key",
      syncPassword = "dandan",
      gitLabToken = "glpat-xyz"
    )

    val token = ConfigLineCodec.encode(snapshot)
    val decoded = ConfigLineCodec.decode(token)

    assertNotNull(decoded)
    assertEquals(snapshot, decoded)
  }

  @Test
  fun `extract token works from noisy clipboard text`() {
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
  fun `decode applies defaults for empty optional fields`() {
    val raw = """
      baseUrl=https://x
      repo=repo1
      mirrorInsecureTls=true
      offlineGenerateOnly=true
      gitLabBaseUrl=
      gitLabProject=
      gitLabInsecureTls=false
      gitRemoteName=
      pullBackDefaultMode=
      mirrorApiKey=
      syncPassword=
      gitLabToken=
    """.trimIndent()

    val token = "LGM_CONFIG_V1:" + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
    val decoded = ConfigLineCodec.decode(token)

    assertNotNull(decoded)
    assertEquals("origin", decoded.gitRemoteName)
    assertEquals("new-branch", decoded.pullBackDefaultMode)
    assertEquals(true, decoded.offlineGenerateOnly)
  }

  @Test
  fun `extract token supports markdown and case-insensitive prefix`() {
    val snapshot = ConfigSnapshot(
      baseUrl = "https://192.168.0.104:443",
      repo = "default",
      mirrorInsecureTls = true,
      offlineGenerateOnly = false,
      simpleUiMode = false,
      gitLabBaseUrl = "",
      gitLabProject = "",
      gitLabInsecureTls = false,
      gitRemoteName = "origin",
      pullBackDefaultMode = "new-branch",
      mirrorApiKey = "k",
      syncPassword = "p",
      gitLabToken = ""
    )

    val token = ConfigLineCodec.encode(snapshot)
    val lower = token.replace("LGM_CONFIG_V2:", "lgm_config_v2:")
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
    assertEquals(snapshot.repo, decoded.repo)
  }

  @Test
  fun `decode supports direct key value payload`() {
    val raw = """
      baseUrl=https://192.168.0.104:443
      repo=default
      mirrorInsecureTls=true
      offlineGenerateOnly=false
      simpleUiMode=true
      gitLabBaseUrl=
      gitLabProject=
      gitLabInsecureTls=false
      gitRemoteName=origin
      pullBackDefaultMode=new-branch
      mirrorApiKey=abc
      syncPassword=xyz
      gitLabToken=
    """.trimIndent()

    val decoded = ConfigLineCodec.decode(raw)
    assertNotNull(decoded)
    assertEquals("https://192.168.0.104:443", decoded.baseUrl)
    assertEquals("default", decoded.repo)
    assertEquals(true, decoded.simpleUiMode)
    assertEquals("abc", decoded.mirrorApiKey)
    assertEquals("xyz", decoded.syncPassword)
  }

  @Test
  fun `extractOrNull handles ansi colored text around prefix`() {
    val token = ConfigLineCodec.encode(
      ConfigSnapshot(
        baseUrl = "https://x",
        repo = "r",
        mirrorInsecureTls = true,
        offlineGenerateOnly = false,
        simpleUiMode = false,
        gitLabBaseUrl = "",
        gitLabProject = "",
        gitLabInsecureTls = false,
        gitRemoteName = "origin",
        pullBackDefaultMode = "new-branch",
        mirrorApiKey = "k",
        syncPassword = "p",
        gitLabToken = ""
      )
    )

    val noisy = "\u001B[32m${token.substring(0, 16)}\u001B[0m${token.substring(16)}"
    val extracted = ConfigLineCodec.extractOrNull(noisy)
    assertNotNull(extracted)
    val decoded = ConfigLineCodec.decode(extracted)
    assertNotNull(decoded)
    assertEquals("https://x", decoded.baseUrl)
    assertEquals("r", decoded.repo)
  }

  @Test
  fun `extractOrNull can recover from payload-only text`() {
    val token = ConfigLineCodec.encode(
      ConfigSnapshot(
        baseUrl = "https://payload-only",
        repo = "repo1",
        mirrorInsecureTls = true,
        offlineGenerateOnly = false,
        simpleUiMode = false,
        gitLabBaseUrl = "",
        gitLabProject = "",
        gitLabInsecureTls = false,
        gitRemoteName = "origin",
        pullBackDefaultMode = "new-branch",
        mirrorApiKey = "k",
        syncPassword = "p",
        gitLabToken = ""
      )
    )
    val payloadOnly = token.replaceFirst(":", "=")
    val extracted = ConfigLineCodec.extractOrNull(payloadOnly)
    assertNotNull(extracted)
    val decoded = ConfigLineCodec.decode(extracted)
    assertNotNull(decoded)
    assertEquals("https://payload-only", decoded.baseUrl)
    assertEquals("repo1", decoded.repo)
  }
}
