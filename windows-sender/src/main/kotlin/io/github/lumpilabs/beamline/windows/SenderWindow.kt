package io.github.lumpilabs.beamline.windows

import io.github.lumpilabs.beamline.protocol.Protocol
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** The window: pick a TV and a screen, start and stop, and answer the pairing question. */
object SenderWindow {
  fun open() {
    SwingUtilities.invokeLater {
      runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
      MainFrame().apply {
        setLocationRelativeTo(null)
        isVisible = true
      }
    }
  }
}

/** One entry of the TV list. */
private sealed interface TvChoice {
  data class Tv(val address: TvAddress) : TvChoice {
    override fun toString(): String = if (address.name == address.host) address.host else "${address.name} (${address.host})"
  }

  data object Other : TvChoice {
    override fun toString(): String = Text["tv_other"]
  }
}

private class MainFrame : JFrame(Text["app_name"]), MirrorSession.Listener {
  private val settings = Settings.load()
  private val tvModel = DefaultComboBoxModel<TvChoice>()
  private val tvBox = JComboBox(tvModel)
  private val screenBox = JComboBox(Screen.list().toTypedArray())
  private val lowLatency = JCheckBox(Text["low_latency"], settings.lowLatency)
  private val button = JButton(Text["start"])
  private val status = JLabel(Text["status_idle"])
  private val detail = JLabel(" ")
  private val discovery = Discovery { tvs -> SwingUtilities.invokeLater { showTvs(tvs) } }

  /** TVs typed in or used before, which discovery may not find (another network, multicast blocked). */
  private val manualTvs = mutableListOf<TvAddress>()
  private var session: MirrorSession? = null
  private var pairingDialog: JDialog? = null
  private var lastChoice: TvChoice? = null
  private var updatingTvs = false
  private var closing = false

  init {
    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    iconImages = listOf(16, 32, 48, 64, 256).map(::icon)
    settings.lastTv?.let { manualTvs += it }
    showTvs(emptyList())
    tvBox.addActionListener { onTvChosen() }
    screenBox.renderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean) =
        super.getListCellRendererComponent(list, (value as? Screen)?.let(::screenText) ?: value, index, selected, focus)
    }
    screenBox.selectedIndex = settings.screen.coerceIn(0, (screenBox.itemCount - 1).coerceAtLeast(0))
    button.addActionListener { if (session == null) startMirroring() else session?.stop(null) }
    status.font = status.font.deriveFont(Font.BOLD)
    detail.foreground = Color.GRAY
    contentPane = JPanel(GridBagLayout()).apply {
      border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
      row(0, JLabel(Text["label_tv"]), tvBox)
      row(1, JLabel(Text["label_screen"]), screenBox)
      add(lowLatency, constraints(1, 2))
      add(button, constraints(1, 3, fill = false).apply { anchor = GridBagConstraints.WEST })
      add(status, constraints(0, 4, width = 2).apply { insets = Insets(14, 0, 0, 0) })
      add(detail, constraints(0, 5, width = 2))
    }
    addWindowListener(object : WindowAdapter() {
      override fun windowClosing(e: WindowEvent) = close()
    })
    pack()
    minimumSize = size
    discovery.start()
  }

  private fun JPanel.row(y: Int, label: JLabel, field: JComboBox<*>) {
    add(label, constraints(0, y, fill = false).apply { insets = Insets(4, 0, 4, 12) })
    add(field, constraints(1, y))
  }

  private fun constraints(x: Int, y: Int, width: Int = 1, fill: Boolean = true) =
    GridBagConstraints().apply {
      gridx = x
      gridy = y
      gridwidth = width
      weightx = if (x == 1 || width > 1) 1.0 else 0.0
      this.fill = if (fill) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
      anchor = GridBagConstraints.WEST
      insets = Insets(4, 0, 4, 0)
    }

  private fun screenText(screen: Screen): String =
    Text[if (screen.primary) "screen_main" else "screen_item", screen.index + 1, screen.width, screen.height]

  /** Found TVs first, then those typed in, then "Other address…"; the choice stays where it can. */
  private fun showTvs(found: List<TvAddress>) {
    val selected = (tvBox.selectedItem as? TvChoice.Tv)?.address ?: settings.lastTv
    val all = (found + manualTvs.filter { manual -> found.none { it.host == manual.host && it.port == manual.port } })
    updatingTvs = true
    tvModel.removeAllElements()
    all.forEach { tvModel.addElement(TvChoice.Tv(it)) }
    tvModel.addElement(TvChoice.Other)
    val keep = (0 until tvModel.size).map(tvModel::getElementAt).firstOrNull {
      it is TvChoice.Tv && selected != null && it.address.host == selected.host && it.address.port == selected.port
    }
    tvModel.selectedItem = keep ?: tvModel.getElementAt(0)
    updatingTvs = false
    lastChoice = tvModel.selectedItem as TvChoice
    if (found.isEmpty() && all.isEmpty() && session == null) detail.text = Text["tv_searching"]
    if (found.isNotEmpty() && session == null && detail.text == Text["tv_searching"]) detail.text = " "
  }

  private fun onTvChosen() {
    if (updatingTvs) return
    if (tvBox.selectedItem != TvChoice.Other) {
      lastChoice = tvBox.selectedItem as? TvChoice
      return
    }
    // Asked once the list has closed, so the dialog doesn't fight the popup for focus.
    SwingUtilities.invokeLater { askAddress() }
  }

  /** Asks for a TV's address and picks it. Returns false when the user cancelled. */
  private fun askAddress(): Boolean {
    val typed = JOptionPane.showInputDialog(this, Text["address_prompt"], Text["app_name"], JOptionPane.PLAIN_MESSAGE)?.trim()
    val address = typed?.takeIf { it.isNotEmpty() }?.let(::parseAddress)
    updatingTvs = true
    try {
      if (address == null) {
        tvModel.selectedItem = lastChoice
        return false
      }
      manualTvs.removeAll { it.host == address.host && it.port == address.port }
      manualTvs += address
      val choice = TvChoice.Tv(address)
      tvModel.insertElementAt(choice, tvModel.size - 1)
      tvModel.selectedItem = choice
      lastChoice = choice
      return true
    } finally {
      updatingTvs = false
    }
  }

  private fun parseAddress(text: String): TvAddress? {
    val port = text.substringAfterLast(':', "").toIntOrNull()
    val host = if (port != null) text.substringBeforeLast(':') else text
    return if (host.isBlank()) null else TvAddress(host, port ?: Protocol.DEFAULT_PORT)
  }

  private fun startMirroring() {
    if (tvBox.selectedItem == TvChoice.Other && !askAddress()) return
    val tv = (tvBox.selectedItem as? TvChoice.Tv)?.address ?: return
    val screen = screenBox.selectedItem as? Screen ?: return
    settings.lastTv = tv
    settings.screen = screen.index
    settings.lowLatency = lowLatency.isSelected
    setControlsEnabled(false)
    button.text = Text["stop"]
    detail.text = " "
    session = MirrorSession(tv, screen.index, lowLatency.isSelected, settings, this).also { it.start() }
  }

  private fun setControlsEnabled(enabled: Boolean) {
    tvBox.isEnabled = enabled
    screenBox.isEnabled = enabled
    lowLatency.isEnabled = enabled
  }

  override fun onStatus(status: MirrorStatus) =
    SwingUtilities.invokeLater {
      // A fixed width, so a long message wraps instead of widening the window.
      this.status.text = "<html><body style='width: ${STATUS_WIDTH}px'>${escape(status.text())}</body></html>"
      if (status is MirrorStatus.Mirroring) {
        detail.text = Text[if (status.hardware) "encoder" else "encoder_software", status.encoder]
      }
      pairingDialog?.dispose()
      pairingDialog = null
      if (status is MirrorStatus.Pairing) askPairing(status)
      pack()
    }

  override fun onEnded() =
    SwingUtilities.invokeLater {
      session = null
      pairingDialog?.dispose()
      pairingDialog = null
      setControlsEnabled(true)
      button.text = Text["start"]
      if (closing) finish()
    }

  /** Asks whether the TV shows the same code; the answer goes to the session. */
  private fun askPairing(status: MirrorStatus.Pairing) {
    val current = session ?: return
    val code = JLabel(MirrorStatus.spaced(status.code)).apply {
      font = Font(Font.MONOSPACED, Font.BOLD, font.size * 3)
    }
    val matches = Text["pairing_matches"]
    val differs = Text["pairing_differs"]
    val pane = JOptionPane(
      arrayOf(Text["pairing_question", status.tv], code),
      JOptionPane.QUESTION_MESSAGE,
      JOptionPane.YES_NO_OPTION,
      null,
      arrayOf(matches, differs),
      differs,
    )
    val dialog = pane.createDialog(this, Text["pairing_title", status.tv])
    dialog.isModal = false
    dialog.addWindowListener(object : WindowAdapter() {
      override fun windowClosed(e: WindowEvent) {
        if (pairingDialog === dialog) current.answerPairing(pane.value == matches)
      }
    })
    pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY) { if (pane.value != JOptionPane.UNINITIALIZED_VALUE) dialog.dispose() }
    pairingDialog = dialog
    dialog.isVisible = true
  }

  private fun close() {
    closing = true
    discovery.close()
    val current = session ?: return finish()
    current.stop(null)
    // The goodbye to the TV gets a moment, but not forever.
    Timer(2_000) { finish() }.apply { isRepeats = false }.start()
  }

  private fun finish() {
    dispose()
    exitProcess(0)
  }

  companion object {
    const val STATUS_WIDTH = 340

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** The Beamline mark of the phone app: a screen with a beam entering it. */
    fun icon(size: Int): BufferedImage {
      val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
      val g = image.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      val scale = size / 72.0
      g.color = Color(0x0E, 0x1A, 0x2B)
      g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), size * 0.25, size * 0.25))
      // The phone icon's 108-unit canvas, cut to its middle 72 units.
      g.scale(scale, scale)
      g.translate(-18.0, -18.0)
      g.color = Color(0x3D, 0xD6, 0xF5)
      g.fill(Path2D.Double().apply {
        moveTo(27.0, 80.0)
        lineTo(50.0, 49.0)
        lineTo(61.0, 58.0)
        closePath()
      })
      g.color = Color.WHITE
      g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
      g.draw(RoundRectangle2D.Double(34.0, 37.0, 44.0, 30.0, 8.0, 8.0))
      g.drawLine(48, 74, 64, 74)
      g.dispose()
      return image
    }
  }
}
