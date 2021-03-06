/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import akka.actor.{ Stash, Actor, ActorLogging, Status }
import sbt.{ IO, PathFinder }
import scala.util.control.NonFatal
import scala.collection.mutable
import activator.templates.repository.RepositoryException

/** This class represents the inability to resolve a template from the internet, and not some other fatal error. */
case class ResolveTemplateException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)
/**
 * This actor provides an implementation of the tutorial cache
 * that allows us to handle failures via actor-restart and threading
 * via single-access to the cache.
 *
 * Although, if we use lucene, we could have multi-threaded access, best
 * not to assume technology for now.
 *
 * TODO - Add a manager in front of this actor that knows how to update the lucene index and reboot this guy.
 */
class TemplateCacheActor(provider: IndexDbProvider, baseDir: File, remotes: IndexedSeq[RemoteTemplateRepository], autoUpdate: Boolean)
  extends Actor with ForwardingExceptions with ActorLogging with Stash {
  import TemplateCacheActor._

  // Using a ListMap to preserve order
  val indexes = mutable.ListMap[RemoteTemplateRepository, (IndexDb, CacheProperties)]()

  def receive = {
    case InitializeNormal =>
      unstashAll()
      context become receiveNormal
    case InitializeFailure(e) =>
      unstashAll()
      context become receiveFailure(e)
    case _ =>
      // used to prevent race - if someone sends a message to the actor before it is properly initialized we just stash it
      stash()
  }

  def receiveNormal: Receive = forwardingExceptionsToFutures {
    case GetTemplate(id: String) => sender ! TemplateResult(getTemplate(id))
    case GetTutorial(id: String) => sender ! TutorialResult(getTutorial(id))
    case SearchTemplates(query, max) => sender ! TemplateQueryResult(searchTemplates(query, max))
    case SearchTemplateByName(name) => sender ! TemplateQueryResult(searchTemplateByName(name))
    case ListTemplates => sender ! TemplateQueryResult(listTemplates)
    case ListFeaturedTemplates => sender ! TemplateQueryResult(listFeaturedTemplates)
  }

  def receiveFailure(e: Throwable): Receive = {
    case _ => sender ! Status.Failure(e)
  }

  def listTemplates = indexes.flatMap(value => fillMetadata(value._1, value._2._1.metadata)).toSet
  def listFeaturedTemplates = indexes.flatMap(value => fillMetadata(value._1, value._2._1.featured)).toSet

  def searchTemplates(query: String, max: Int): Iterable[TemplateMetadata] =
    indexes.flatMap(value => fillMetadata(value._1, value._2._1.search(query, max)))

  def searchTemplateByName(name: String): Iterable[TemplateMetadata] =
    indexes.flatMap(value => fillMetadata(value._1, value._2._1.templateByName(name)))

  private def allFilesIn(dir: File): Seq[File] = (PathFinder(dir).*** --- PathFinder(dir)).get
  def getTutorial(id: String): Option[Tutorial] = {
    val tutorialDir = new java.io.File(getTemplateDirAndEnsureLocal(id), Constants.TUTORIAL_DIR)
    if (tutorialDir.exists) {
      val fileMappings = for {
        file <- allFilesIn(tutorialDir)
        if !file.isDirectory
        relative <- IO.relativize(tutorialDir, file)
        if !relative.isEmpty
      } yield relative -> file
      Some(Tutorial(id, fileMappings.toMap))
    } else None
  }

  def getTemplate(id: String): Option[Template] = {
    indexes.flatMap {
      case (remote, (index, _)) =>
        index.template(id) match {
          case Some(metadata) =>
            try {
              val localDir = getTemplateDirAndEnsureLocalForRepo(id, remote)
              val fileMappings = for {
                file <- allFilesIn(localDir)
                if !file.isDirectory
                relative <- IO.relativize(localDir, file)
                if !relative.isEmpty
                if !(relative startsWith Constants.TUTORIAL_DIR)
              } yield file -> relative
              val meta = TemplateMetadata(
                persistentConfig = metadata,
                locallyCached = true)
              Some(Template(meta, fileMappings))
            } catch {
              case ex: ResolveTemplateException =>
                if (ex.getCause ne null)
                  log.warning(s"${ex.getMessage}: ${ex.getCause.getClass.getName}: ${ex.getCause.getMessage}")
                else
                  log.warning(ex.getMessage)
                None
            }
          case _ => None
        }
    }.headOption
  }

  private def fillMetadata(repo: RemoteTemplateRepository, metadata: Iterable[IndexStoredTemplateMetadata]): Iterable[TemplateMetadata] =
    metadata map { meta =>
      val locallyCached = isTemplateCached(repo.name, meta.id)
      TemplateMetadata(persistentConfig = meta, locallyCached = locallyCached)
    }

  // TODO - return a file that is friendly for having tons of stuff in it,
  //i.e. maybe we take the first N of the id and use that as a directory first.
  private def templateLocation(repoName: String, id: String): File =
    new java.io.File(new java.io.File(baseDir, repoName), id)
  /**
   * Determines if we've cached a template.
   *  TODO - check other files?
   */
  private def isTemplateCached(repoName: String, id: String): Boolean =
    templateLocation(repoName, id).exists

  private def getTemplateDirAndEnsureLocal(id: String): File = {
    // Only resolve template for a repository that contains it,
    // so that we can throw an exception when it should be there but we can't download it
    indexes.filter(_._2._1.template(id).isDefined).map {
      case (remote, (index, _)) =>
        val templateDir = templateLocation(remote.name, id)
        if (templateDir.exists) templateDir
        else {
          try remote.resolveTemplateTo(id, templateLocation(remote.name, id))
          catch {
            case NonFatal(ex) =>
              // We have a non-fatal exception, let's make sure the template directory is GONE, so the cache is consistent.
              if (templateDir.isDirectory) sbt.IO delete templateDir
              // Also, we should probably wrap this in some sort of exception we can use later...
              throw ResolveTemplateException(s"Unable to download template: $id", ex)
          }
        }
    }.headOption.getOrElse(throw new RuntimeException("Template not found"))
  }

  private def getTemplateDirAndEnsureLocalForRepo(id: String, remote: RemoteTemplateRepository): File = {
    indexes(remote)._1.template(id).map { _ =>
      val templateDir = templateLocation(remote.name, id)
      if (templateDir.exists) templateDir
      else {
        try remote.resolveTemplateTo(id, templateLocation(remote.name, id))
        catch {
          case NonFatal(ex) =>
            // We have a non-fatal exception, let's make sure the template directory is GONE, so the cache is consistent.
            if (templateDir.isDirectory) sbt.IO delete templateDir
            // Also, we should probably wrap this in some sort of exception we can use later...
            throw ResolveTemplateException(s"Unable to download template: $id", ex)
        }
      }
    }.headOption.getOrElse(throw new RuntimeException("Template not found"))
  }

  override def preStart(): Unit = {
    val messages = remotes.map(preStartRepository)
    if (messages.forall(_.isInstanceOf[InitializeNormal.type])) {
      self ! InitializeNormal
    } else {
      messages.foreach(self ! _)
    }
  }

  private def preStartRepository(remote: RemoteTemplateRepository): InitializeMessage = {
    // Our index is underneath the cache location...
    val cachePropertiesFile = CacheProperties.propertiesFileForRepository(baseDir, remote.name, log)
    val props = new CacheProperties(cachePropertiesFile)
    val indexFile = CacheProperties.indexFileForRepository(baseDir, remote.name)
    val fatalError = try {
      if (autoUpdate) {
        remote.ifNewIndexProperties(props.cacheIndexHash.getOrElse("")) { newProps =>
          val newHash = newProps.cacheIndexHash.getOrElse(throw RepositoryException("No index hash field in downloaded index.properties file", null))
          // download the new index
          remote.resolveIndexTo(indexFile, newHash)
          // if the download succeeds, update our local props
          props.cacheIndexHash = newHash
          props.save("Updating the local index properties.")
          log.debug(s"Saved new template index hash $newHash to ${props.location.getAbsolutePath}")
        }
      }

      // We may have the latest index properties but not have the actual index; this
      // happens in the seed generator, which requires an index properties to be provided.
      if (!indexFile.exists) {
        props.cacheIndexHash foreach { currentHash =>
          log.info(s"We have index hash $currentHash but haven't downloaded that index - attempting to download it now.")
          remote.resolveIndexTo(indexFile, currentHash)
        }
      }

      if (indexFile.exists) {
        props.cacheIndexHash map { currentHash =>
          log.debug(s"Updated to latest template catalog $currentHash, saved in ${props.location.getAbsolutePath}")
        } getOrElse {
          // this is a little weird but let's go with it
          log.debug(s"We appear to have a template catalog ${indexFile.getAbsolutePath}, but we don't know its hash")
        }
        // if indexFile exists, we can always proceed
        None
      } else {
        // We get here if things are weird: no exception was thrown but we didn't end up downloading an index.
        // If autoUpdate=true this is probably a bug in our code. If it's false, then it means we have no
        // cache and also aren't supposed to update to get one, so we are hosed.
        if (!cachePropertiesFile.exists || !props.cacheIndexHash.isDefined) {
          if (autoUpdate)
            Some(RepositoryException(s"We don't have ${cachePropertiesFile.getAbsolutePath} with an index hash in it, even though we should have downloaded one", null))
          else
            Some(RepositoryException(s"Template catalog updates are disabled, and ${cachePropertiesFile.getAbsolutePath} didn't already exist with an index hash in it", null))
        } else {
          Some(RepositoryException(s"We don't have ${indexFile.getAbsolutePath} even though we have ${cachePropertiesFile.getAbsolutePath} with hash ${props.cacheIndexHash.get}", null))
        }
      }
    } catch {
      case NonFatal(e) =>
        // We get here if downloading the properties file or the index itself threw an exception
        if (indexFile.exists) {
          log.warning(s"Failed to update template catalog so using the old one (${e.getClass.getName}): ${e.getMessage})")
          None
        } else {
          Some(e)
        }
    }
    fatalError map { e =>
      log.error(e, s"Could not find a template catalog. (${e.getClass.getName}: ${e.getMessage}")
      InitializeFailure(e)
    } getOrElse {
      // try actually opening the index
      try {
        val index = provider.open(indexFile)
        indexes.put(remote, (index, props))
        InitializeNormal
      } catch {
        case NonFatal(e) =>
          log.error(e, s"Could not open the template catalog. (${e.getClass.getName}: ${e.getMessage}")
          InitializeFailure(e)
      }
    }
  }

  override def postStop(): Unit = {
    indexes.values.foreach {
      case (index, _) =>
        if (index != null) {
          index.close()
        }
    }
  }

}

object TemplateCacheActor {
  case class GetTemplate(id: String)
  case class GetTutorial(id: String)
  case class SearchTemplates(query: String, max: Int = 0)
  case class SearchTemplateByName(name: String)
  case object ListTemplates
  case object ListFeaturedTemplates

  case class TemplateResult(template: Option[Template])
  case class TemplateQueryResult(templates: Iterable[TemplateMetadata])
  case class TutorialResult(tutorial: Option[Tutorial])

  sealed trait InitializeMessage
  case object InitializeNormal extends InitializeMessage
  case class InitializeFailure(e: Throwable) extends InitializeMessage
}
