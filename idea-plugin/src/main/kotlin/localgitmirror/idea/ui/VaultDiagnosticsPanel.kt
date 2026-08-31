package localgitmirror.idea.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class VaultDiagnosticsPanel : JPanel(BorderLayout()) {

    private val vaultPathLabel = JBLabel("Vault path: --")
    private val casSizeLabel = JBLabel("CAS size: --")
    private val artifactCountLabel = JBLabel("Artifacts: --")
    private val pendingWantedLabel = JBLabel("Pending wanted: --")
    private val conflictsLabel = JBLabel("Conflicts: --")
    private val lastBackupLabel = JBLabel("Last backup: --")

    private val statusLabel = JBLabel("").apply {
        font = JBUI.Fonts.smallFont()
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    var onRefresh: (() -> Unit)? = null

    init {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(12, 16)

        val title = JBLabel("Vault Diagnostics").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(14f))
        }

        content.add(title)
        content.add(Box.createVerticalStrut(12))
        content.add(vaultPathLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(casSizeLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(artifactCountLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(pendingWantedLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(conflictsLabel)
        content.add(Box.createVerticalStrut(4))
        content.add(lastBackupLabel)
        content.add(Box.createVerticalStrut(12))

        val refreshButton = JButton("Refresh").apply {
            addActionListener { onRefresh?.invoke() }
        }
        content.add(refreshButton)
        content.add(Box.createVerticalStrut(8))
        content.add(statusLabel)

        add(content, BorderLayout.CENTER)

        vaultPathLabel.font = JBUI.Fonts.smallFont()
        casSizeLabel.font = JBUI.Fonts.label()
        artifactCountLabel.font = JBUI.Fonts.label()
        pendingWantedLabel.font = JBUI.Fonts.label()
        conflictsLabel.font = JBUI.Fonts.label()
        lastBackupLabel.font = JBUI.Fonts.label()
    }

    fun updateStatus(
        vaultPath: String,
        casSizeBytes: Long,
        artifactCount: Int,
        pendingWantedCount: Int,
        conflictsCount: Int,
        lastBackup: String?
    ) {
        vaultPathLabel.text = "Vault path: $vaultPath"
        casSizeLabel.text = "CAS size: ${"%.1f".format(casSizeBytes / 1_048_576.0)} MB"
        artifactCountLabel.text = "Artifacts: $artifactCount"
        pendingWantedLabel.text = "Pending wanted: $pendingWantedCount"
        conflictsLabel.text = "Conflicts: $conflictsCount"
        lastBackupLabel.text = "Last backup: ${lastBackup ?: "Never"}"
        statusLabel.text = ""
    }

    fun updateStatusError(message: String) {
        vaultPathLabel.text = "Vault path: --"
        casSizeLabel.text = "CAS size: --"
        artifactCountLabel.text = "Artifacts: --"
        pendingWantedLabel.text = "Pending wanted: --"
        conflictsLabel.text = "Conflicts: --"
        lastBackupLabel.text = "Last backup: --"
        statusLabel.text = message
    }
}