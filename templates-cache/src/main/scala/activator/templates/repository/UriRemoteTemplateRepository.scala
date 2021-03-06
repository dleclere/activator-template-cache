/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package templates
package repository

import cache._
import java.net.URI
import java.net.URL
import java.io.File
import sbt.IO
import scala.util.control.NonFatal
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import com.amazonaws.services.s3.model.{ GetObjectRequest, GetObjectMetadataRequest }
import akka.event.LoggingAdapter
import java.util.UUID
import java.net.HttpURLConnection
import com.amazonaws.AmazonServiceException

/**
 *  This dude resolves files from a URI-based repo.  We use the sbt
 *  IO.download function to pull down URIs.  So, pretty much just simple HTTP
 *  downloads or local file copies are supported.
 */
class UriRemoteTemplateRepository(base: URI, log: LoggingAdapter) extends RemoteTemplateRepository {
  protected val layout = new Layout(base)

  override lazy val name: String = {
    downloadNewIndexProperties("") {
      props => props.catalogName.getOrElse(throw RepositoryException("index properties file didn't have a catalog name.", null))
    } { optionalThrowable =>
      optionalThrowable.map(throw _).getOrElse(throw RepositoryException("Unable to download latest index hash", null))
    }
  }

  // wrapper around IO.download that logs what's happening
  private def download(url: URL, dest: File): Unit = {
    log.debug(s"Downloading url $url underneath base $base")
    // todo: lower timeout on this
    try IO.download(url, dest)
    catch {
      case e: Exception =>
        log.error(s"Failed to download $url: ${e.getClass.getName}: ${e.getMessage}")
        throw RepositoryException(s"Failed to download $url: ${e.getClass.getName}: ${e.getMessage}", e)
    }
  }

  private def exists(url: URL): Boolean = {
    log.debug(s"Checking HEAD for url $url underneath base $base")
    try {
      url.openConnection() match {
        case http: HttpURLConnection =>
          http.setRequestMethod("HEAD")
          http.getResponseCode match {
            case 200 =>
              true
            case whatever =>
              log.debug(s"response code $whatever from HEAD on $url")
              false
          }
        case whatever =>
          throw new Exception("Got weird non-http connection " + whatever)
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to download $url: ${e.getClass.getName}: ${e.getMessage}")
        throw RepositoryException(s"Failed to download $url: ${e.getClass.getName}: ${e.getMessage}", e)
    }
  }

  protected def makeProxyableClientConfiguration(): ClientConfiguration = {
    val config = new ClientConfiguration().withProtocol(Protocol.HTTPS)
    // Check if we need a proxy
    def doIf(prop: String)(f: String => Unit): Unit = {
      sys.props.get(prop).foreach(f)
    }
    // TODO - According to spec, https + http proxies can be different for java.
    //   Are our users using different ones?
    doIf("http.proxyHost")(config.setProxyHost)
    doIf("http.proxyPort")(portString => config.setProxyPort(portString.toInt))
    doIf("http.proxyUser")(config.setProxyUsername)
    doIf("http.proxyPassword")(config.setProxyPassword)

    // Set a low timeout on this thing of 5 seconds
    config.setConnectionTimeout(5000)

    config
  }

  protected def makeClient(): AmazonS3Client = {
    new AmazonS3Client(
      new AnonymousAWSCredentials(),
      makeProxyableClientConfiguration())
  }

  private def cleanLocation(path: String): String =
    if (path startsWith "/") path drop 1
    else path
  // Note: Thanks to CLOUD FRONT on AMazon S3, we'd like to
  // grab the more current index file using an anonymous S3 client.
  protected def downloadFromS3(url: URI, dest: File): Unit = try {
    log.debug(s"Downloading S3 bucket $url underneath base $base")
    val client = makeClient()

    val (bucket, key) = getBucketAndKey(url)
    val request = new GetObjectRequest(bucket, key)
    client.getObject(request, dest)
  } catch {
    case e: Exception =>
      throw RepositoryException(s"Failed to download $url from s3: ${e.getClass.getName}: ${e.getMessage}", e)
  }

  protected def existsFromS3(url: URI): Boolean = try {
    log.debug(s"Checking existence of $url underneath base $base")
    val client = makeClient()

    val (bucket, key) = getBucketAndKey(url)
    val request = new GetObjectMetadataRequest(bucket, key)
    client.getObjectMetadata(request)
    true
  } catch {
    case e: AmazonServiceException =>
      if (e.getStatusCode == 404) {
        log.debug(s"404 on S3 object $url: ${e.getStatusCode}: ${e.getErrorCode}: ${e.getMessage}")
        false
      } else {
        throw RepositoryException(s"Failed to check existence of $url on s3: ${e.getClass.getName}: ${e.getMessage}", e)
      }
    case e: Exception =>
      // Exception includes AmazonClientException which is an error before the
      // http request even gets started (largely should not happen)
      throw RepositoryException(s"Failed to check existence of $url on s3: ${e.getClass.getName}: ${e.getMessage}", e)
  }

  private def downloadTryingS3First(uri: URI, toFile: File): File = {
    // TODO - Should we be going directly to s3 here?
    // we are trying it to bypass CloudFront cache.
    try downloadFromS3(uri, toFile)
    catch {
      // Our backup for local-file based testing...
      case ex: RepositoryException =>
        log.warning(s"Failed to grab s3 bucket, attempting to use http to '$uri'. (${ex.msg})")
        download(uri.toURL, toFile)
    }
    toFile
  }

  private def existsTryingS3First(uri: URI): Boolean = {
    // TODO - Should we be going directly to s3 here?
    // we are trying it to bypass CloudFront cache.
    try existsFromS3(uri)
    catch {
      // Our backup for local-file based testing...
      case ex: RepositoryException =>
        log.warning(s"Failed to grab s3 bucket, attempting to use http to '$uri'. (${ex.msg})")
        exists(uri.toURL)
    }
  }

  protected def getBucketAndKey(url: URI): (String, String) =
    url.getHost -> cleanLocation(url.getRawPath)

  def resolveTemplateTo(templateId: String, localDir: File): File = {
    IO.withTemporaryDirectory { tmpDir =>
      val tmpFile = new File(tmpDir, "template.zip")
      download(layout.template(templateId).toURL, tmpFile)
      IO.createViaTemporary(localDir) { templateDir =>
        IO.unzip(tmpFile, templateDir)
      }
    }
    localDir
  }

  def hasNewIndexProperties(currentHash: String): Boolean = {
    downloadNewIndexProperties(currentHash)(_ => true)(_ => false)
  }

  def resolveLatestIndexHash(): String = {
    downloadNewIndexProperties("") {
      props => props.cacheIndexHash.getOrElse(throw RepositoryException("index properties file didn't have a hash in it", null))
    } { optionalThrowable =>
      optionalThrowable.map(throw _).getOrElse(throw RepositoryException("Unable to download latest index hash", null))
    }
  }

  def ifNewIndexProperties(currentHash: String)(onNewIndex: CacheProperties => Unit): Unit = {
    downloadNewIndexProperties(currentHash)(onNewIndex)(_ => ())
  }

  // use currentHash = "" to always download
  private def downloadNewIndexProperties[T](currentHash: String)(onNewIndex: CacheProperties => T)(onNotNewIndex: Option[Throwable] => T): T = {
    try {
      IO.withTemporaryDirectory { tmpDir =>
        val indexProps = new File(tmpDir, "index.properties")
        resolveIndexProperties(indexProps)
        val props = new CacheProperties(indexProps)
        def recentEnough = props.cacheIndexBinaryMajorVersion == Constants.INDEX_BINARY_MAJOR_VERSION &&
          props.cacheIndexBinaryIncrementVersion >= Constants.INDEX_BINARY_INCREMENT_VERSION
        val newHash = props.cacheIndexHash.getOrElse(throw RepositoryException("Downloaded catalog properties didn't contain a new index hash", null))
        def differentCache = newHash != currentHash
        if (differentCache && recentEnough) {
          log.debug(s"Found a new template catalog with hash $newHash (we had $currentHash before)")
          onNewIndex(props)
        } else {
          if (differentCache) {
            // throw in this case so there's an error message
            throw RepositoryException(s"Template catalog has been updated, but we can only use catalogs with version ${Constants.INDEX_BINARY_MAJOR_VERSION}.${Constants.INDEX_BINARY_INCREMENT_VERSION} and we found ${props.cacheIndexBinaryMajorVersion}.${props.cacheIndexBinaryIncrementVersion}", null)
          } else {
            log.debug(s"Template catalog is unchanged since we last downloaded it, we already have $currentHash.")
            onNotNewIndex(None)
          }
        }
      }
    } catch {
      // In the event of download failure, just assume we don't have a newer index.
      case NonFatal(e) =>
        log.warning(s"Failed to download new template catalog properties: ${e.getClass.getName}: ${e.getMessage}")
        onNotNewIndex(Some(e))
    }
  }

  def resolveIndexTo(indexDirOrFile: File, indexHash: String): Unit = {
    IO.withTemporaryDirectory { tmpDir =>
      val indexZip = new File(tmpDir, "index.zip")
      val url = layout.index(indexHash).toURL
      download(url, indexZip)
      val zipHash = activator.hashing.hash(indexZip)
      if (zipHash != indexHash)
        throw RepositoryException(s"Expected hash of $url to be $indexHash but it was $zipHash", null)

      IO.createViaTemporary(indexDirOrFile) { indexExpanded =>
        IO.unzip(indexZip, indexExpanded)
      }
    }
  }

  /**
   * Downloads the current index properties into
   */
  def resolveIndexProperties(localPropsFile: File): File =
    downloadTryingS3First(layout.currentIndexTag, localPropsFile)

  override def resolveMinimalActivatorDist(toFile: File, activatorVersion: String): File =
    downloadTryingS3First(layout.minimalActivatorDist(activatorVersion), toFile)

  override def templateBundleURI(activatorVersion: String,
    uuid: UUID,
    templateName: String): URI =
    layout.templateBundle(activatorVersion, uuid.toString, templateName)

  override def authorLogoURI(uuid: UUID): URI =
    layout.authorLogo(uuid.toString)

  override def templateBundleExists(activatorVersion: String,
    uuid: UUID,
    templateName: String): Boolean =
    existsTryingS3First(templateBundleURI(activatorVersion, uuid, templateName))

  override def templateZipURI(uuid: UUID): URI =
    layout.template(uuid.toString)
}
