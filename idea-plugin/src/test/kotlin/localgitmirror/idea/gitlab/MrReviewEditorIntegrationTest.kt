package localgitmirror.idea.gitlab

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.ui.LafManager
import com.intellij.ide.actions.QuickChangeLookAndFeel
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JTextArea
import javax.swing.JFrame
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertAll

class MrReviewEditorIntegrationTest {
  @TempDir lateinit var repo: File

  @Test
  fun `native editor opens once per MR and displays read only Git diff with full draft`() {
    git("init", "-b", "main")
    git("config", "user.email", "review@example.com")
    git("config", "user.name", "Review")
    File(repo, "Example.java").writeText("class Example {\n  int value = 1;\n}\n")
    commit("base")
    git("checkout", "-b", "feature")
    File(repo, "Example.java").writeText("class Example {\n  int value = 2;\n}\n")
    commit("change")
    git("checkout", "main")
    val body = "Check the boundary condition before publishing this finding. ".repeat(18).trim()
    File(repo, ".mr-notes").mkdirs()
    File(repo, ".mr-notes/replies-!24.md").writeText(MrReplies.render(24, "feature",
      listOf(MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", "Example.java", 2, false, body))))
    val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("DocCacheReview", repo.toPath(), false).fixture
    fixture.setUp()
    assertAll("native review lifecycle", {
      EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val nativeManager = FileEditorManagerImpl(fixture.project,
          (fixture.project as ComponentManagerEx).getCoroutineScope().childScope())
        fixture.project.replaceService(FileEditorManager::class.java, nativeManager, fixture.testRootDisposable)
        Disposer.register(fixture.testRootDisposable, nativeManager)
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
          .getService(MirrorSettingsService::class.java).state
        settings.machineRole = "home"
        settings.gitlabUrl = ""
        val row = MrReviewService.MrRowItem(24, "Boundary check", "feature", "", 0, 0,
          emptyList(), MrReviewService.Source.CACHE, null, repo = "review-test", targetBranch = "main")
        assertTrue(FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.any { it is MrReviewEditorProvider })

        openMrReviewEditor(fixture.project, row)
        openMrReviewEditor(fixture.project, row)

        val manager = FileEditorManager.getInstance(fixture.project)
        val files = manager.openFiles.filterIsInstance<MrReviewVirtualFile>()
        assertEquals(1, files.size)
        val editor = manager.getEditors(files.single()).single()
        assertEquals("DocCache Review", editor.name)
        val panel = editor.component
        PlatformTestUtil.waitWithEventsDispatching("Review diff did not load", {
          panel.descendants().filterIsInstance<com.intellij.openapi.editor.impl.EditorComponentImpl>().any()
        }, 30)
        assertTrue(panel.descendants().filterIsInstance<JTextArea>().any { it.text == body })
        assertFalse(editor.isModified)
        val codeEditors = com.intellij.openapi.editor.EditorFactory.getInstance().allEditors
          .filter { it.project == fixture.project }
        assertTrue(codeEditors.any { it.document.text.contains("int value = 1") })
        assertTrue(codeEditors.any { it.document.text.contains("int value = 2") })
        assertTrue(codeEditors.all { it.isViewer })
        if (!GraphicsEnvironment.isHeadless()) capture(panel)
        manager.closeFile(files.single())
        assertTrue(manager.openFiles.none { it is MrReviewVirtualFile })
      }
    }, {
      EdtTestUtil.runInEdtAndWait<RuntimeException> { fixture.tearDown() }
    })
    assertEquals("main", git("branch", "--show-current").trim())
  }

  private fun Component.descendants(): Sequence<Component> = sequence {
    yield(this@descendants)
    if (this@descendants is Container) for (child in components) yieldAll(child.descendants())
  }

  private fun Component.layoutTree() {
    if (this is Container) {
      doLayout()
      components.forEach { it.layoutTree() }
    }
  }

  private fun capture(panel: JComponent) {
    if (System.getProperty("review.ui.qa") == null) return
    val evidence = File("build/review-qa").apply { mkdirs() }
    val frame = JFrame("DocCache Review QA").apply { contentPane.add(panel) }
    val manager = LafManager.getInstance()
    val previous = manager.currentLookAndFeel
    try {
      frame.setSize(1440, 900)
      frame.isVisible = true
      val themes = listOf("light" to requireNotNull(manager.defaultLightLaf), "dark" to requireNotNull(manager.defaultDarkLaf))
      for ((theme, laf) in themes) {
        QuickChangeLookAndFeel.switchLafAndUpdateUI(manager, laf, false)
        for (width in listOf(1000, 1440)) {
        frame.setSize(width, 900)
        frame.validate()
        repeat(3) { panel.layoutTree() }
        for (area in panel.descendants().filterIsInstance<JTextArea>().filter { it.text.length > 500 }) {
          val lastLine = requireNotNull(area.modelToView2D(area.document.length))
          assertTrue(lastLine.maxY <= area.height, "Last line is clipped at width $width: ${lastLine.maxY} > ${area.height}")
        }
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> try { panel.paint(graphics) } finally { graphics.dispose() } }
          ImageIO.write(image, "png", File(evidence, "review-$theme-$width.png"))
        }
      }
    } finally {
      frame.contentPane.remove(panel)
      frame.dispose()
      QuickChangeLookAndFeel.switchLafAndUpdateUI(manager, previous, false)
    }
  }

  private fun commit(message: String) { git("add", "."); git("commit", "-m", message) }
  private fun git(vararg args: String): String {
    val process = ProcessBuilder(listOf("git", *args)).directory(repo).redirectErrorStream(true).start()
    val result = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { result }
    return result
  }
}
