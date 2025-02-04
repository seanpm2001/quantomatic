package quanto.gui

import scala.swing.{Component, Dialog, FileChooser, Publisher}
import java.io.{File, FileNotFoundException, IOException}

import scala.swing.event.Event
import quanto.data._
import quanto.util.json.JsonParseException
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.prefs.Preferences

import javax.swing.JOptionPane

abstract class DocumentEvent extends Event
case class DocumentChanged(sender: Document) extends DocumentEvent
case class DocumentSaved(sender: Document) extends DocumentEvent
case class DocumentReplaced(sender: Document) extends DocumentEvent
case class DocumentRequestingNaturalFocus(sender: Document) extends DocumentEvent

/**
 * For an object connected to a single file. Provides an undo stack, tracks changes, and gives
 * convenience functions for loading, saving, etc.
 */

abstract class Document extends Publisher {
  var file: Option[File] = None
  private val _undoStack = new UndoStack
  def undoStack = _undoStack
  def unsavedChanges : Boolean
  def description: String
  def fileExtension: String

  protected def clearDocument()
  protected def saveDocument(f: File)
  protected def loadDocument(f: File)
  protected def parent : Component

  /**
   * Given a file, export the document to that
   * file (or directory) as tikz diagram(s) where applicable
   */
  protected def exportDocument(f: File) = { }

  protected def resetDocumentInfo() {
    undoStack.clear()
    file = None
    publish(DocumentChanged(this))
  }

  def clear() {
    clearDocument()
    resetDocumentInfo()
  }

  def save(fopt: Option[File] = None) {
    fopt.orElse(file).foreach { f =>
      try {
        saveDocument(f)
        file = Some(f)
        publish(DocumentSaved(this))
      } catch {
        case _: IOException => errorDialog("save", "file unwriteable")
        case e: Exception =>
          errorDialog("save", "unexpected error")
          e.printStackTrace()
      }
    }
  }

  def load(f : File) = {
    var success = false
    try {
      file = Some(f)
      loadDocument(f)
      publish(DocumentReplaced(this))
      publish(DocumentChanged(this))
      success = true
    } catch {
      case e: JsonParseException => errorDialog("load", "mal-formed JSON: " + e.getMessage)
      case e: GraphLoadException => errorDialog("load", "invalid graph: " + e.getMessage)
      case e: RuleLoadException => errorDialog("load", "invalid rule: " + e.getMessage)
      case e: DerivationLoadException => errorDialog("load", "invalid derivation: " + e.getMessage)
      case e: FileNotFoundException => errorDialog("load", "file not found")
      case e: IOException => errorDialog("load", "file unreadable")
      case e: Exception =>
        errorDialog("load", "unexpected error")
        e.printStackTrace()
    }

    success
  }

  def titleDescription : String = {
    val base = file.map(f => f.getName).getOrElse("Untitled").replaceAll("\\.[^.]*$", "")
    if (unsavedChanges) {
      base + "*"
    } else {
      base
    }
//    val name : String = file.map(f => f.getName).getOrElse("Untitled")
//    // If there is a description then use in instead of the file extension
//    val nameDescription = if (description.length > 0) name.replaceAll("\\.[^.]*$", "") + " " + description else name
//    val nameDescriptionChanges = nameDescription + (if (unsavedChanges) "*" else "")
//    nameDescriptionChanges
  }

  /**
   * Try to save a document in current file if it exists and prompt the
   * SaveAs dialog if it doesn't
   * @return true if the file was saved and false if it wasn't as per user
   * interaction
   */
  def trySave() = {
    file match {
      case Some(_) => save()
      case None => showSaveAsDialog()
    }

    /* see if saving was successful */
    file match {
      case Some(_) => true
      case None => false
    }
  }

  /**
   * Show a dialog asking the user whether to save or discard
   * any changes before closing the document, or to cancel closing
   * the document
   * @return true if the document can be closed, false otherwise
   * (as per user decission)
   */
  def promptUnsaved(): Boolean = {
    if (unsavedChanges) {
//      val choice = Dialog.showOptions(
//        title = "Unsaved changes",
//        message = "Do you want to save your changes or discard them?",
//        entries = "Save" :: "Discard" :: "Cancel" :: Nil,
//        initial = 0
//      )

      val choice = JOptionPane.showOptionDialog(null,
        "Do you want to save your changes or discard them?",
        "Unsaved changes in "+titleDescription,
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.WARNING_MESSAGE, null,
        List("Save", "Discard", "Cancel").toArray,
        "Save")

      // scala swing dialogs implementation is dumb, here's what I found :
      // Result(0) = Save, Result(1) = Discard, Result(2) = Cancel

      if (choice == 0) trySave()
      else choice == 1
    } else true
  }

  def promptExists(f: File): Boolean = {
    if (f.exists()) {
      JOptionPane.showConfirmDialog(null,
        "File exists, do you wish to overwrite?",
        "File exists",
        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
//      Dialog.showConfirmation(
//        title = "File exists",
//        message = "File exists, do you wish to overwrite?") == Dialog.Result.Yes
    } else true
  }


  def errorDialog(action: String, reason: String) {
//    Dialog.showMessage(
//      title = "Error",
//      message = "Cannot " + action + " file (" + reason + ")",
//      messageType = Dialog.Message.Error)
    JOptionPane.showMessageDialog(null,
      "Cannot " + action + " file (" + reason + ")",
      "Error",
      JOptionPane.ERROR_MESSAGE)
  }

  def previousDir_=(f: File) {
    val dir = if (f.isDirectory) f.getPath
              else f.getParent
    if (dir != null) {
      val prefs = Preferences.userRoot().node(this.getClass.getName)
      prefs.put("previousDir", dir)
    }
  }

  def previousDir: File = {
    val prefs = Preferences.userRoot().node(this.getClass.getName)
    new File(prefs.get("previousDir", System.getProperty("user.home")))
  }

  def showSaveAsDialog(rootDir: Option[String] = None) {
    val chooser = new FileChooser()
    chooser.peer.setCurrentDirectory(rootDir match {
      case Some(d) => new File(d)
      case None => previousDir
    })
    chooser.fileFilter = new FileNameExtensionFilter("Quantomatic " + description + " File (*." + fileExtension + ")", fileExtension)
    chooser.showSaveDialog(parent) match {
      case FileChooser.Result.Approve =>
        val p = chooser.selectedFile.getAbsolutePath
        val file = new File(if (p.endsWith("." + fileExtension)) p else p + "." + fileExtension)
        if (promptExists(file)) save(Some(file))
      case _ =>
    }
  }

  /**
   * Shows a dialog allowing the user to choose a file
   * to which the document will be exported as a tikz
   * diagram(s)
   */
  def export(rootDir: Option[String] = None) {
    val chooser = new FileChooser()
    chooser.peer.setCurrentDirectory(rootDir match {
      case Some(d) => new File(d)
      case None => previousDir
    })
    chooser.fileFilter = new FileNameExtensionFilter("Quantomatic " + description + " File (*.tikz)", "tikz")
    chooser.showSaveDialog(parent) match {
      case FileChooser.Result.Approve =>
        val p = chooser.selectedFile.getAbsolutePath
        val file = new File(if (p.endsWith(".tikz")) p else p + ".tikz")
        if (promptExists(file)) exportDocument(file)
      case _ =>
    }
  }

  def showOpenDialog(rootDir: Option[String] = None) {
    if (promptUnsaved()) {
      val chooser = new FileChooser()
      chooser.peer.setCurrentDirectory(rootDir match {
        case Some(d) => new File(d)
        case None => previousDir
      })
      chooser.fileFilter = new FileNameExtensionFilter("Quantomatic " + description + " File (*." + fileExtension + ")", fileExtension)
      chooser.showOpenDialog(parent) match {
        case FileChooser.Result.Approve =>
          previousDir = chooser.selectedFile
          load(chooser.selectedFile)
        case _ =>
      }
    }
  }

  // any time the graph state changes in a meaningful way, an undo is registered
  listenTo(_undoStack)
  reactions += {
    case UndoRegistered(_) =>
      publish(DocumentChanged(this))
  }

  // Publishes a request for the view to focus on whatever is "correct" for the document.
  // E.g. give focus to the text area if you open a .py file
  def focusOnNaturalComponent() : Unit = {
    publish(DocumentRequestingNaturalFocus(this))
  }
}

trait HasDocument {
  def document: Document
}
