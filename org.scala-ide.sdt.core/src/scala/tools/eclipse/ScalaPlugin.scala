/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import scala.tools.eclipse.ui.semantic.highlighting.SemanticHighlightingPresenter
import org.eclipse.jdt.core.IJavaProject
import scala.collection.mutable.HashMap
import scala.util.control.ControlThrowable
import org.eclipse.core.resources.{ IFile, IProject, IResourceChangeEvent, IResourceChangeListener, ResourcesPlugin }
import org.eclipse.core.runtime.{ CoreException, FileLocator, IStatus, Platform, Status }
import org.eclipse.core.runtime.content.IContentTypeSettings
import org.eclipse.jdt.core.{ ElementChangedEvent, IElementChangedListener, JavaCore, IJavaElementDelta }
import org.eclipse.jdt.internal.core.{ JavaModel, JavaProject, PackageFragment, PackageFragmentRoot }
import org.eclipse.jdt.internal.core.util.Util
import org.eclipse.jdt.internal.ui.javaeditor.IClassFileEditorInput
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.swt.graphics.Color
import org.eclipse.ui.{ IEditorInput, IFileEditorInput, PlatformUI }
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.osgi.framework.BundleContext
import scala.tools.eclipse.javaelements.{ ScalaElement, ScalaSourceFile }
import scala.tools.eclipse.util.OSGiUtils.pathInBundle
import scala.tools.eclipse.templates.ScalaTemplateManager
import scala.tools.eclipse.internal.logging.Tracer
import scala.tools.eclipse.internal.logging.Defensive
import scala.tools.eclipse.markoccurrences.UpdateOccurrenceAnnotationsService

object ScalaPlugin { 
  var plugin : ScalaPlugin = _
}

class ScalaPlugin extends AbstractUIPlugin with IResourceChangeListener with IElementChangedListener {
  ScalaPlugin.plugin = this
  
  def pluginId = "org.scala-ide.sdt.core"
  def compilerPluginId = "org.scala-ide.scala.compiler"
  def libraryPluginId = "org.scala-ide.scala.library"
    
  def wizardPath = pluginId + ".wizards"
  def wizardId(name : String) = wizardPath + ".new" + name
  def classWizId = wizardId("Class")
  def traitWizId = wizardId("Trait")
  def objectWizId = wizardId("Object")
  def applicationWizId = wizardId("Application")
  def projectWizId = wizardId("Project")
  def netProjectWizId = wizardId("NetProject")
  
  def editorId = "scala.tools.eclipse.ScalaSourceFileEditor"
  def builderId = pluginId + ".scalabuilder"
  def natureId = pluginId + ".scalanature"  
  def launchId = "org.scala-ide.sdt.launching"
  val scalaCompiler = "SCALA_COMPILER_CONTAINER"
  val scalaLib = "SCALA_CONTAINER"
  def scalaCompilerId  = launchId + "." + scalaCompiler
  def scalaLibId  = launchId + "." + scalaLib
  def launchTypeId = "scala.application"
  def problemMarkerId = pluginId + ".problem"
  
  // Retained for backwards compatibility
  val oldPluginId = "ch.epfl.lamp.sdt.core"
  val oldLibraryPluginId = "scala.library"
  val oldNatureId = oldPluginId + ".scalanature"
  val oldBuilderId = oldPluginId + ".scalabuilder"
  val oldLaunchId = "ch.epfl.lamp.sdt.launching"
  val oldScalaLibId  = oldLaunchId + "." + scalaLib
  
  val scalaFileExtn = ".scala"
  val javaFileExtn = ".java"
  val jarFileExtn = ".jar"
  
  val scalaCompilerBundle = Platform.getBundle(ScalaPlugin.plugin.compilerPluginId)
  val compilerClasses = pathInBundle(scalaCompilerBundle, "/lib/scala-compiler.jar")
  val continuationsClasses = pathInBundle(scalaCompilerBundle, "/lib/continuations.jar")
  val compilerSources = pathInBundle(scalaCompilerBundle, "/lib/scala-compiler-src.jar")
  
  val scalaLibBundle = Platform.getBundle(ScalaPlugin.plugin.libraryPluginId)
  val libClasses = pathInBundle(scalaLibBundle, "/lib/scala-library.jar")
  val libSources = pathInBundle(scalaLibBundle, "/lib/scala-library-src.jar")
  val dbcClasses = pathInBundle(scalaLibBundle, "/lib/scala-dbc.jar")
  val dbcSources = pathInBundle(scalaLibBundle, "/lib/scala-dbc-src.jar")
  val swingClasses = pathInBundle(scalaLibBundle, "/lib/scala-swing.jar")
  val swingSources = pathInBundle(scalaLibBundle, "/lib/scala-swing-src.jar")

  lazy val templateManager = new ScalaTemplateManager()
  lazy val updateOccurrenceAnnotationsService = new UpdateOccurrenceAnnotationsService()
  lazy val reconcileListeners = new ReconcileListeners()

  private val projects = new HashMap[IProject, ScalaProject]
  
  override def start(context : BundleContext) = {
    super.start(context)
    
    ResourcesPlugin.getWorkspace.addResourceChangeListener(this, IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.POST_CHANGE)
    JavaCore.addElementChangedListener(this)
    Platform.getContentTypeManager.
      getContentType(JavaCore.JAVA_SOURCE_CONTENT_TYPE).
        addFileSpec("scala", IContentTypeSettings.FILE_EXTENSION_SPEC)
    Util.resetJavaLikeExtensions // TODO Is this still needed?
    PlatformUI.getWorkbench.getEditorRegistry.setDefaultEditor("*.scala", editorId)
    
    println("Scala compiler bundle: " + scalaCompilerBundle.getLocation)
    PerspectiveFactory.updatePerspective
  }

  override def stop(context : BundleContext) = {
    ResourcesPlugin.getWorkspace.removeResourceChangeListener(this)

    super.stop(context)
  }
  
  def workspaceRoot = ResourcesPlugin.getWorkspace.getRoot
    
  def getJavaProject(project : IProject) = JavaCore.create(project) 

  def getScalaProject(project : IProject) : ScalaProject = projects.synchronized {
    projects.get(project) match {
      case Some(scalaProject) => scalaProject
      case None =>
        val scalaProject = new ScalaProject(project)
        projects(project) = scalaProject
        scalaProject
    }
  }
  
  def getScalaProject(input : IEditorInput) : ScalaProject = input match {
    case fei : IFileEditorInput => getScalaProject(fei.getFile.getProject)
    case cfei : IClassFileEditorInput => getScalaProject(cfei.getClassFile.getJavaProject.getProject)
    case _ => null
  }

  def isScalaProject(project: IJavaProject): Boolean = isScalaProject(project.getProject)
  
  def isScalaProject(project : IProject): Boolean =
    try {
      project != null && project.isOpen && (project.hasNature(natureId) || project.hasNature(oldNatureId))
    } catch {
      case _ : CoreException => false
    }

  //TODO merge behavior with/into elementChanged ?
  override def resourceChanged(event : IResourceChangeEvent) {
    if ((event.getType & IResourceChangeEvent.PRE_CLOSE) != 0) {
      event.getResource match {
        case project : IProject =>  projects.synchronized{
          projects.get(project) match {
            case Some(scalaProject) =>
              Defensive.tryOrLog {
                projects.remove(project)
                scalaProject.resetCompilers(null)
              }
            case None => 
          }
        }
        case _ => ()
      }

    }
  }

  //TODO invalidate (set dirty) cache about classpath, compilers,... when sourcefolders, classpath change
  override def elementChanged(event : ElementChangedEvent) {
    if ((event.getType & ElementChangedEvent.POST_CHANGE) == 0) {
      return
    }
    val delta = event.getDelta
    (delta.getKind, delta.getFlags) match {
      //let it do (close the project) else findRemovedSource raise exception
      case (IJavaElementDelta.CHANGED, IJavaElementDelta.F_CLOSED) => ()
      // TODO check if the code below is always usefull
      case (IJavaElementDelta.REMOVED, _) => delta.getElement match {
        case _ : JavaModel => {
          def findRemovedSource(deltas : Array[IJavaElementDelta]) : Unit = {
            deltas.foreach { delta =>
              delta.getElement match {
                case ssf : ScalaSourceFile if (delta.getKind == IJavaElementDelta.REMOVED) =>
                  val project = ssf.getJavaProject.getProject
                  if (project.isOpen)
                    getScalaProject(project).withPresentationCompilerIfExists { _.discardSourceFile(ssf) }
                case _ : PackageFragment | _ : PackageFragmentRoot | _ : JavaProject =>
                  findRemovedSource(delta.getAffectedChildren)
                case _ =>
              }
            }
          }
          findRemovedSource(delta.getAffectedChildren)
        }
        case _ => () //ignore
      }
      case (_, _) => () //ignore
    }
  }

  def logInfo(msg : String, t : Option[Throwable] = None) : Unit = log(IStatus.INFO, msg, t)

  def logWarning(msg : String, t : Option[Throwable] = None) : Unit = log(IStatus.WARNING, msg, t)

  def logError(t : Throwable) : Unit = logError(t.getClass + ":" + t.getMessage, t)
  
  def logError(msg : String, t : Throwable) : Unit = {
    val t1 = if (t != null) t else { val ex = new Exception ; ex.fillInStackTrace ; ex }    
    log(IStatus.ERROR, msg, Some(t1))
  }
  
  private def log(level : Int, msg : String, t : Option[Throwable]) : Unit = {
    val status1 = new Status(level, pluginId, level, msg, t.getOrElse(null))
    getLog.log(status1)

    val status = t match {
      case Some(ce : ControlThrowable) =>
        val t2 = { val ex = new Exception ; ex.fillInStackTrace ; ex }
        val status2 = new Status(
           level, pluginId, level,
          "Incorrectly logged ControlThrowable: "+ce.getClass.getSimpleName+"("+ce.getMessage+")", t2)
        getLog.log(status2)
      case _ =>
    }
  }
  
  def bundlePath = check {
    val bundle = getBundle 
    val bpath = bundle.getEntry("/")
    val rpath = FileLocator.resolve(bpath)
    rpath.getPath
  }.getOrElse("unresolved")

  final def check[T](f : => T) =
    try {
      Some(f)
    } catch {
      case e : Throwable =>
        logError(e)
        None
    }

  def isBuildable(file : IFile) = (file.getName.endsWith(scalaFileExtn) || file.getName.endsWith(javaFileExtn))
}

