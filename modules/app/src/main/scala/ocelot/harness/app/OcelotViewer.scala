package ocelot.harness.app

import java.awt.{BorderLayout, Color, Dimension, FlowLayout, Graphics, GraphicsEnvironment, Toolkit}
import java.awt.datatransfer.DataFlavor
import java.awt.event.{
  ActionEvent,
  KeyAdapter,
  KeyEvent,
  MouseAdapter,
  MouseEvent,
  MouseWheelEvent,
  WindowAdapter,
  WindowEvent
}
import java.awt.image.BufferedImage
import java.nio.file.{Path, Paths}
import java.util.concurrent.{
  ArrayBlockingQueue,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.swing.{
  BorderFactory,
  BoxLayout,
  JButton,
  JComboBox,
  JFrame,
  JLabel,
  JPanel,
  JScrollPane,
  JSpinner,
  ScrollPaneConstants,
  SpinnerNumberModel,
  SwingUtilities,
  Timer,
  WindowConstants
}

import scala.util.Try
import scala.util.control.NonFatal

import ocelot.harness.app.protocol.{AppExitCode, LoopbackConnection, RpcClientFailure}
import ocelot.harness.app.viewer.{
  ViewerCell,
  ViewerClockStatus,
  ViewerGeometry,
  ViewerProtocol,
  ViewerViewportSize
}
import ocelot.harness.core.artifact.{RenderOptions, ScreenRenderer}
import ocelot.harness.core.project.ScreenId
import ocelot.harness.core.workspace.ScreenSnapshot

private[app] final case class ViewerOptions(
    projectRoot: Path,
    initialScreen: Option[ScreenId],
    scale: Int,
    refreshMillis: Long
)

private[app] object ViewerOptions {
  val Usage =
    "usage: ocelot-viewer [--project <path>] [--screen <id>] [--scale <1..8>] [--refresh-ms <50..2000>]"

  def parse(arguments: Vector[String]): Either[String, ViewerOptions] = {
    def loop(
        remaining: Vector[String],
        projectRoot: Path,
        initialScreen: Option[ScreenId],
        scale: Int,
        refreshMillis: Long
    ): Either[String, ViewerOptions] =
      remaining match {
        case Vector() => Right(ViewerOptions(projectRoot, initialScreen, scale, refreshMillis))
        case Vector("--project") => Left("--project requires a path")
        case Vector("--project", path, tail @ _*) =>
          loop(
            tail.toVector,
            Paths.get(path).toAbsolutePath.normalize(),
            initialScreen,
            scale,
            refreshMillis
          )
        case Vector("--screen") => Left("--screen requires an ID")
        case Vector("--screen", value, tail @ _*) =>
          ScreenId
            .parse(value)
            .left
            .map(reason => s"invalid screen ID: $reason")
            .flatMap(id => loop(tail.toVector, projectRoot, Some(id), scale, refreshMillis))
        case Vector("--scale") => Left("--scale requires a value")
        case Vector("--scale", value, tail @ _*) =>
          boundedInt(value, "scale", 1, 8).flatMap(parsed =>
            loop(tail.toVector, projectRoot, initialScreen, parsed, refreshMillis)
          )
        case Vector("--refresh-ms") => Left("--refresh-ms requires a value")
        case Vector("--refresh-ms", value, tail @ _*) =>
          boundedInt(value, "refresh interval", 50, 2000).flatMap(parsed =>
            loop(tail.toVector, projectRoot, initialScreen, scale, parsed.toLong)
          )
        case _ => Left(Usage)
      }

    loop(
      arguments,
      Paths.get(".").toAbsolutePath.normalize(),
      initialScreen = None,
      scale = 1,
      refreshMillis = 50L
    )
  }

  private def boundedInt(
      value: String,
      name: String,
      minimum: Int,
      maximum: Int
  ): Either[String, Int] =
    Try(value.toInt).toOption match {
      case Some(parsed) if parsed >= minimum && parsed <= maximum => Right(parsed)
      case _ => Left(s"$name must be between $minimum and $maximum")
    }
}

object OcelotViewer {
  def main(arguments: Array[String]): Unit = {
    val exitCode = run(arguments.toVector)
    if (exitCode != AppExitCode.Success) System.exit(exitCode)
  }

  private[app] def run(arguments: Vector[String]): Int = {
    if (arguments == Vector("--help") || arguments == Vector("help")) {
      System.out.println(ViewerOptions.Usage)
      AppExitCode.Success
    } else
      ViewerOptions.parse(arguments) match {
        case Left(message) => fail(AppExitCode.Usage, message)
        case Right(_) if GraphicsEnvironment.isHeadless =>
          fail(AppExitCode.Runtime, "a graphical desktop is required")
        case Right(options) =>
          LoopbackConnection.open(options.projectRoot) match {
            case Left(error) => fail(error.exitCode, error.message)
            case Right(connection) =>
              openViewer(connection, options) match {
                case Left(error) =>
                  connection.close()
                  fail(error.exitCode, error.message)
                case Right((screenIds, initial)) =>
                  SwingUtilities.invokeLater(() => {
                    val window = new ViewerWindow(connection, options, screenIds, initial)
                    window.open()
                  })
                  AppExitCode.Success
              }
          }
      }
  }

  private def openViewer(
      connection: LoopbackConnection,
      options: ViewerOptions
  ): Either[RpcClientFailure, (Vector[ScreenId], ScreenId)] =
    connection.call("workspace.describe").flatMap { workspace =>
      ViewerProtocol
        .decodeScreenIds(workspace)
        .left
        .map(message => RpcClientFailure(AppExitCode.Protocol, message))
        .flatMap { screens =>
          if (screens.isEmpty)
            Left(RpcClientFailure(AppExitCode.Domain, "project has no screens"))
          else
            options.initialScreen match {
              case Some(requested) if !screens.exists(_ == requested) =>
                Left(
                  RpcClientFailure(
                    AppExitCode.Domain,
                    s"unknown screen: ${requested.value}"
                  )
                )
              case Some(requested) => Right(screens -> requested)
              case None            => Right(screens -> screens.head)
            }
        }
    }

  private def fail(exitCode: Int, message: String): Int = {
    System.err.println(s"ERROR: $message")
    if (exitCode == AppExitCode.Usage) System.err.println(ViewerOptions.Usage)
    exitCode
  }
}

private final class ViewerWindow(
    connection: LoopbackConnection,
    options: ViewerOptions,
    screenIds: Vector[ScreenId],
    initialScreen: ScreenId
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)
  private val selectedScreen = new AtomicReference[String](initialScreen.value)
  private val lastRendered = new AtomicReference[(String, Long)](null)
  private val currentClock = new AtomicReference[ViewerClockStatus](null)
  private val refreshPending = new AtomicBoolean(false)
  private val worker = new ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](128),
    ViewerWindow.workerThreadFactory,
    new ThreadPoolExecutor.AbortPolicy()
  )
  private val refreshTimer =
    new Timer(options.refreshMillis.toInt, (_: ActionEvent) => submitRefresh())
  private val frame = new JFrame("Ocelot Harness Viewer")
  private val status = new JLabel("Connecting…")
  private val screenPanel = new ViewerScreenPanel(handleGesture, handleScroll, handleKey)
  private val scroll = new JScrollPane(screenPanel)
  private val controls = new JPanel()
  private val selector = new JComboBox[String](screenIds.map(_.value).toArray)
  private val clockToggle = new JButton("Pause")
  private val clockStep = new JButton("Step")
  private val targetTps = new JSpinner(new SpinnerNumberModel(20, 1, 1000, 1))
  private val setRate = new JButton("Set TPS")

  def open(): Unit = {
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
    frame.addWindowListener(new WindowAdapter {
      override def windowClosing(event: WindowEvent): Unit = close()
    })

    selector.setSelectedItem(initialScreen.value)
    selector.addActionListener((_: ActionEvent) => {
      selectedScreen.set(selector.getSelectedItem.toString)
      lastRendered.set(null)
      submitRefresh()
      screenPanel.requestFocusInWindow()
    })

    val paste = new JButton("Paste clipboard")
    clockStep.setEnabled(false)
    paste.addActionListener((_: ActionEvent) => pasteClipboard())
    clockToggle.addActionListener((_: ActionEvent) => toggleClock())
    clockStep.addActionListener((_: ActionEvent) =>
      submitClock("simulation.step", ujson.Obj("count" -> 1))
    )
    setRate.addActionListener((_: ActionEvent) => {
      val tps = targetTps.getValue.asInstanceOf[Number].intValue()
      submitClock("simulation.rate", ujson.Obj("tps" -> tps))
    })

    val projectActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0))
    projectActions.add(selector)
    projectActions.add(paste)

    val clockActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0))
    clockActions.add(clockToggle)
    clockActions.add(clockStep)

    val rateActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0))
    rateActions.add(targetTps)
    rateActions.add(setRate)

    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS))
    controls.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4))
    status.setPreferredSize(new Dimension(300, status.getPreferredSize.height))
    controls.add(projectActions)
    controls.add(clockActions)
    controls.add(rateActions)
    controls.add(status)

    scroll.setBorder(BorderFactory.createEmptyBorder())
    scroll.setViewportBorder(null)
    scroll.getViewport.setBackground(Color.BLACK)
    frame.add(controls, BorderLayout.NORTH)
    frame.add(scroll, BorderLayout.CENTER)
    frame.setLocationByPlatform(true)

    refreshTimer.setCoalesce(true)
    refreshTimer.start()
    submitRefresh()
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      refreshTimer.stop()
      worker.shutdownNow()
      connection.close()
      if (SwingUtilities.isEventDispatchThread) frame.dispose()
      else SwingUtilities.invokeLater(() => frame.dispose())
    }
  }

  private def refresh(): Unit = {
    if (!closed.get()) {
      connection.call("simulation.status") match {
        case Left(error) => showError(error.message)
        case Right(clockValue) =>
          ViewerProtocol.decodeClock(clockValue) match {
            case Left(error) => showError(error)
            case Right(clock) =>
              val id = selectedScreen.get()
              connection.call("screen.read", ujson.Obj("screenId" -> id)) match {
                case Left(error) => showError(error.message)
                case Right(value) =>
                  ViewerProtocol.decodeScreen(value) match {
                    case Left(error) => showError(error)
                    case Right(snapshot) =>
                      renderIfChanged(id, snapshot)
                      showStatus(id, snapshot, clock)
                  }
              }
          }
      }
    }
  }

  private def renderIfChanged(id: String, snapshot: ScreenSnapshot): Unit = {
    val prior = lastRendered.get()
    if (prior == null || prior._1 != id || prior._2 != snapshot.revision) {
      ScreenRenderer.render(snapshot, RenderOptions(options.scale)) match {
        case Left(error) => showError(error.message)
        case Right(image) =>
          lastRendered.set(id -> snapshot.revision)
          SwingUtilities.invokeLater(() => {
            if (!closed.get() && selectedScreen.get() == id) {
              val dimensionsChanged = screenPanel.update(snapshot, image)
              if (dimensionsChanged || !frame.isVisible) fitFrameToImage(image)
            }
          })
      }
    }
  }

  private def fitFrameToImage(image: BufferedImage): Unit = {
    val desktop = GraphicsEnvironment.getLocalGraphicsEnvironment.getMaximumWindowBounds
    val maximumWidth = math.max(1, desktop.width - 48)
    val maximumHeight = math.max(1, desktop.height - controls.getPreferredSize.height - 72)
    val viewport =
      ViewerViewportSize.fit(image.getWidth, image.getHeight, maximumWidth, maximumHeight)
    scroll.setHorizontalScrollBarPolicy(
      if (viewport.width < image.getWidth) ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
      else ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    )
    scroll.setVerticalScrollBarPolicy(
      if (viewport.height < image.getHeight) ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
      else ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
    )
    scroll.setPreferredSize(new Dimension(viewport.width, viewport.height))
    frame.pack()
    if (!frame.isVisible) frame.setVisible(true)
    screenPanel.requestFocusInWindow()
  }

  private def submitRefresh(): Unit =
    if (!closed.get() && refreshPending.compareAndSet(false, true)) {
      try {
        worker.execute(() => {
          try refresh()
          finally refreshPending.set(false)
        })
      } catch {
        case _: RejectedExecutionException =>
          refreshPending.set(false)
          showError("viewer input queue is full")
      }
    }

  private def submitClock(method: String, params: ujson.Obj): Unit = {
    if (!closed.get()) {
      try {
        worker.execute(() => {
          connection.call(method, params) match {
            case Left(error) => showError(error.message)
            case Right(_)    => refresh()
          }
        })
      } catch {
        case _: RejectedExecutionException => showError("viewer input queue is full")
      }
    }
  }

  private def toggleClock(): Unit = {
    val clock = currentClock.get()
    val method =
      if (clock != null && clock.state == "running") "simulation.pause"
      else "simulation.resume"
    submitClock(method, ujson.Obj())
  }

  private def showStatus(
      id: String,
      snapshot: ScreenSnapshot,
      clock: ViewerClockStatus
  ): Unit = {
    val previousClock = currentClock.getAndSet(clock)
    SwingUtilities.invokeLater(() => {
      if (!closed.get() && selectedScreen.get() == id) {
        clockToggle.setText(if (clock.state == "running") "Pause" else "Resume")
        clockStep.setEnabled(clock.state == "paused")
        if (previousClock == null || previousClock.targetTps != clock.targetTps)
          targetTps.setValue(Integer.valueOf(clock.targetTps))
        status.setForeground(Color.DARK_GRAY)
        val message =
          f"$id • ${snapshot.width}×${snapshot.height} • rev ${snapshot.revision} • " +
            f"${clock.state} • ${clock.targetTps}%d TPS target • ${clock.measuredTps}%.1f measured • " +
            s"${clock.overrunCount} overruns"
        status.setText(message)
        status.setToolTipText(message)
      }
    })
  }

  private def submitInput(params: ujson.Obj): Unit = {
    if (!closed.get()) {
      try {
        worker.execute(() => {
          connection.call("screen.input", params) match {
            case Left(error) => showError(error.message)
            case Right(_)    => refresh()
          }
        })
      } catch {
        case _: RejectedExecutionException => showError("viewer input queue is full")
      }
    }
  }

  private def submitInputs(params: Vector[ujson.Obj]): Unit = {
    if (!closed.get()) {
      try {
        worker.execute(() => {
          val iterator = params.iterator
          var failure: Option[String] = None
          while (iterator.hasNext && failure.isEmpty) {
            connection.call("screen.input", iterator.next()) match {
              case Left(error) => failure = Some(error.message)
              case Right(_)    =>
            }
          }
          failure match {
            case Some(error) => showError(error)
            case None        => refresh()
          }
        })
      } catch {
        case _: RejectedExecutionException => showError("viewer input queue is full")
      }
    }
  }

  private def handleGesture(from: ViewerCell, to: ViewerCell, button: Int): Unit = {
    val id = selectedScreen.get()
    if (from == to)
      submitInputs(
        Vector(ViewerProtocol.touch(id, from, button), ViewerProtocol.drop(id, to, button))
      )
    else {
      val steps = math.max(math.abs(to.x - from.x), math.abs(to.y - from.y)).max(1)
      submitInput(ViewerProtocol.drag(id, from, to, button, steps))
    }
  }

  private def handleScroll(cell: ViewerCell, delta: Int): Unit =
    submitInput(ViewerProtocol.scroll(selectedScreen.get(), cell, delta))

  private def handleKey(key: ViewerKey): Unit = key match {
    case ViewerKey.Typed(text) =>
      submitInput(ViewerProtocol.typeText(selectedScreen.get(), text))
    case ViewerKey.Special(name, down) =>
      submitInput(ViewerProtocol.key(selectedScreen.get(), name, down))
  }

  private def pasteClipboard(): Unit = {
    try {
      val clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
      val text = clipboard.getData(DataFlavor.stringFlavor).asInstanceOf[String]
      submitInput(ViewerProtocol.paste(selectedScreen.get(), text))
      screenPanel.requestFocusInWindow()
    } catch {
      case NonFatal(error) => showError(s"clipboard unavailable: ${message(error)}")
    }
  }

  private def showError(message: String): Unit =
    SwingUtilities.invokeLater(() => {
      if (!closed.get()) {
        status.setForeground(new Color(0xb00020))
        status.setText(message)
        status.setToolTipText(message)
        if (!frame.isVisible) {
          scroll.setPreferredSize(new Dimension(320, 120))
          frame.pack()
          frame.setVisible(true)
        }
      }
    })

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

private sealed trait ViewerKey
private object ViewerKey {
  final case class Typed(text: String) extends ViewerKey
  final case class Special(name: String, down: Boolean) extends ViewerKey
}

private final class ViewerScreenPanel(
    gesture: (ViewerCell, ViewerCell, Int) => Unit,
    scroll: (ViewerCell, Int) => Unit,
    key: ViewerKey => Unit
) extends JPanel {
  private var image: Option[BufferedImage] = None
  private var geometry: Option[ViewerGeometry] = None
  private var mouseStart: Option[(ViewerCell, Int)] = None

  setBackground(Color.BLACK)
  setFocusable(true)
  setFocusTraversalKeysEnabled(false)

  addMouseListener(new MouseAdapter {
    override def mousePressed(event: MouseEvent): Unit = {
      requestFocusInWindow()
      for {
        cell <- cellAt(event)
        button <- ViewerScreenPanel.button(event)
      } mouseStart = Some(cell -> button)
    }

    override def mouseReleased(event: MouseEvent): Unit = {
      val completed = for {
        start <- mouseStart
        end <- cellAt(event)
      } yield (start._1, end, start._2)
      mouseStart = None
      completed.foreach { case (from, to, button) => gesture(from, to, button) }
    }
  })

  addMouseWheelListener((event: MouseWheelEvent) =>
    cellAt(event).foreach(cell => scroll(cell, -event.getWheelRotation))
  )

  addKeyListener(new KeyAdapter {
    override def keyTyped(event: KeyEvent): Unit = {
      val character = event.getKeyChar
      if (
        !event.isControlDown && !event.isAltDown && character != KeyEvent.CHAR_UNDEFINED &&
        !Character.isISOControl(character)
      ) key(ViewerKey.Typed(character.toString))
    }

    override def keyPressed(event: KeyEvent): Unit =
      ViewerScreenPanel
        .specialKey(event.getKeyCode)
        .foreach(name => key(ViewerKey.Special(name, true)))

    override def keyReleased(event: KeyEvent): Unit =
      ViewerScreenPanel
        .specialKey(event.getKeyCode)
        .foreach(name => key(ViewerKey.Special(name, false)))
  })

  def update(snapshot: ScreenSnapshot, rendered: BufferedImage): Boolean = {
    val dimensionsChanged = image.forall(value =>
      value.getWidth != rendered.getWidth || value.getHeight != rendered.getHeight
    )
    image = Some(rendered)
    geometry = Some(
      ViewerGeometry(snapshot.width, snapshot.height, rendered.getWidth, rendered.getHeight)
    )
    setPreferredSize(new Dimension(rendered.getWidth, rendered.getHeight))
    revalidate()
    repaint()
    dimensionsChanged
  }

  override protected def paintComponent(graphics: Graphics): Unit = {
    super.paintComponent(graphics)
    image.foreach(value => graphics.drawImage(value, 0, 0, null))
  }

  private def cellAt(event: MouseEvent): Option[ViewerCell] =
    geometry.flatMap(_.cellAt(event.getX, event.getY))
}

private object ViewerWindow {
  val workerThreadFactory: ThreadFactory = new ThreadFactory {
    override def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "ocelot-harness-viewer")
      thread.setDaemon(true)
      thread
    }
  }
}

private object ViewerScreenPanel {
  def button(event: MouseEvent): Option[Int] = event.getButton match {
    case MouseEvent.BUTTON1 => Some(0)
    case MouseEvent.BUTTON3 => Some(1)
    case _                  => None
  }

  def specialKey(keyCode: Int): Option[String] = keyCode match {
    case KeyEvent.VK_ESCAPE     => Some("escape")
    case KeyEvent.VK_BACK_SPACE => Some("backspace")
    case KeyEvent.VK_TAB        => Some("tab")
    case KeyEvent.VK_ENTER      => Some("enter")
    case KeyEvent.VK_CONTROL    => Some("left-control")
    case KeyEvent.VK_SHIFT      => Some("left-shift")
    case KeyEvent.VK_UP         => Some("up")
    case KeyEvent.VK_LEFT       => Some("left")
    case KeyEvent.VK_RIGHT      => Some("right")
    case KeyEvent.VK_DOWN       => Some("down")
    case KeyEvent.VK_DELETE     => Some("delete")
    case _                      => None
  }
}
