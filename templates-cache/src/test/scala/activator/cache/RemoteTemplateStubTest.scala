/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import org.junit.Assert._
import org.junit._
import java.io.File
import akka.actor._
import concurrent.Await
import concurrent.duration._
import sbt.IO
import java.util.UUID
import java.net.URI

class RemoteTemplateStubTest {

  val FIRST_INDEX_ID = "FIRST INDEX"
  val SECOND_INDEX_ID = "SECOND INDEX"

  var cacheDir: File = null
  var system: ActorSystem = null
  var cache: TemplateCache = null
  implicit val timeout = akka.util.Timeout(1, MINUTES)

  class StubRemoteRepository(val name: String, remoteTemplates: TemplateMetadata*) extends RemoteTemplateRepository {
    def resolveIndexProperties(localPropsFile: File): File = {
      // TODO - implement?
      localPropsFile
    }
    def hasNewIndexProperties(currentHash: String): Boolean =
      SECOND_INDEX_ID != currentHash

    def resolveLatestIndexHash(): String =
      SECOND_INDEX_ID

    def ifNewIndexProperties(currentHash: String)(onNewProperties: CacheProperties => Unit): Unit =
      if (hasNewIndexProperties(currentHash)) {
        IO.withTemporaryDirectory { tmpDir =>
          val propsFile = new File(new File(tmpDir, name), "index.properties")
          val props = new CacheProperties(propsFile)
          props.cacheIndexHash = SECOND_INDEX_ID
          props.catalogName = name
          props.save("saved SECOND_INDEX_ID")
          onNewProperties(props)
        }
      }

    // TODO - Actually alter the index and check to see if we have the new one.
    // Preferable with a new template, not in the existing index.
    def resolveIndexTo(indexDirOrFile: File, currentHash: String): Unit = {
      makeIndex(indexDirOrFile)(remoteTemplates: _*)
    }

    def resolveTemplateTo(templateId: String, localDir: File): File = {
      if (remoteTemplates.exists(_.id == templateId)) {
        // Fake Resolving a remote template
        if (!localDir.exists) IO.createDirectory(localDir)
        IO.write(new File(localDir, "build2.sbt"), """name := "Test2" """)
        val tutorialDir = new File(localDir, Constants.TUTORIAL_DIR)
        IO createDirectory tutorialDir
        IO.write(new File(tutorialDir, "index.html"), "<html></html>")
      }
      localDir
    }

    def templateBundleURI(activatorVersion: String,
      uuid: UUID,
      templateName: String): URI = ???

    def templateBundleExists(activatorVersion: String,
      uuid: UUID,
      templateName: String): Boolean = ???

    def templateZipURI(uuid: UUID): URI = ???

    def authorLogoURI(uuid: UUID): URI = ???

    def resolveMinimalActivatorDist(toFile: File, activatorVersion: String): File = ???
  }

  @Before
  def setup() {
    cacheDir = IO.createTemporaryDirectory
    // TODO - Create an cache...
    makeTestCache(cacheDir, "typesafe-test")
    makeIndex(CacheProperties.indexFileForRepository(cacheDir, "private-test"))(nonLocalPrivateTemplate)
    system = ActorSystem()
    // TODO - stub out remote repo
    cache = DefaultTemplateCache(
      actorFactory = system,
      baseDir = cacheDir,
      remotes = IndexedSeq(
        new StubRemoteRepository("typesafe-test", template1, nonLocalTypesafeTemplate, newNonLocalTemplate),
        new StubRemoteRepository("private-test", nonLocalPrivateTemplate)))
  }

  @Test
  def resolveTemplate(): Unit = {
    val template =
      Await.result(cache.template("ID-1"), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, template1)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveRemoteTemplate(): Unit = {
    val template =
      Await.result(cache.template(nonLocalTypesafeTemplate.id), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, resolvedNonLocalTypesafeTemplate)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build2.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolvePrivateRemoteTemplate(): Unit = {
    val template = Await.result(cache.template(nonLocalPrivateTemplate.id), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, resolvedNonLocalPrivateTemplate)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build2.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveNewRemoteTemplate(): Unit = {
    val template =
      Await.result(cache.template(newNonLocalTemplate.id), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, resolvedNewNonLocalTemplate)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build2.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveTutorial(): Unit = {
    val tutorial =
      Await.result(cache.tutorial(template1.id), Duration(3, MINUTES))
    assertTrue(tutorial.isDefined)
    val hasIndexHtml = tutorial.get.files exists {
      case (name, file) => name == "index.html"
    }
    assertTrue("Failed to find tutorial files!", hasIndexHtml)
  }
  @Test
  def getAllMetadata(): Unit = {
    val metadata =
      Await.result(cache.metadata, Duration(3, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    val hasRemote = metadata exists { _ == nonLocalTypesafeTemplate }
    assertTrue("Failed to find non-local template!", hasRemote)

    val hasNewRemote = metadata exists { _ == newNonLocalTemplate }
    assertTrue("Failed to find new non-local template!", hasNewRemote)
  }

  @Test
  def getFeaturedMetadata(): Unit = {
    val metadata =
      Await.result(cache.featured, Duration(3, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    assertFalse("Featured metadata has unfeatured template.", metadata.exists(_ == nonLocalTypesafeTemplate))
    val hasNewRemote = metadata exists { _ == newNonLocalTemplate }
    assertTrue("Failed to find new non-local template!", hasNewRemote)
  }

  @Test
  def search(): Unit = {
    val metadata =
      Await.result(cache.search("test"), Duration(3, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata in seaarch!", hasMetadata)
  }

  @Test
  def badSearch(): Unit = {
    val metadata =
      Await.result(cache.search("Ralph"), Duration(1, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertFalse("Failed to find metadata in seaarch!", hasMetadata)
  }

  @After
  def tearDown() {
    // Here we always check to ensure the properties are right....
    val cacheProps = new CacheProperties(new File(new File(cacheDir, "typesafe-test"), Constants.CACHE_PROPS_FILENAME))
    assertEquals("Failed to download new metadata index!", Some(SECOND_INDEX_ID), cacheProps.cacheIndexHash)
    system.shutdown()
    IO delete cacheDir
    cacheDir = null
  }

  val template1 = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-1",
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      name = "test-template",
      title = "A Testing Template",
      description = "A template that tests template existance.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = false,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = true)

  val nonLocalTypesafeTemplate = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-2",
      timeStamp = 1L,
      featured = false,
      usageCount = None,
      name = "test-remote-template",
      title = "A Testing Template that is not dowloaded",
      description = "A template that tests template existentialism.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = true,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  val resolvedNonLocalTypesafeTemplate =
    nonLocalTypesafeTemplate.copy(locallyCached = true)

  val newNonLocalTemplate = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-3",
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      name = "test-updated-template",
      title = "A NEW FEATURED TEMPLATE",
      description = "A template that tests template deism.  MONADS.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = false,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  val nonLocalPrivateTemplate = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-4",
      timeStamp = 1L,
      featured = false,
      usageCount = None,
      name = "test-remote-private-template",
      title = "A Testing Template that is not dowloaded from a private repo",
      description = "A template that tests template existentialism.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = true,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  val resolvedNonLocalPrivateTemplate =
    nonLocalPrivateTemplate.copy(locallyCached = true)

  val resolvedNewNonLocalTemplate =
    newNonLocalTemplate.copy(locallyCached = true)

  def makeIndex(dir: File)(templates: TemplateMetadata*): Unit = {
    if (dir.exists) IO.delete(dir)
    val writer = LuceneIndexProvider.write(dir)
    try templates foreach { t => writer insert t.persistentConfig }
    finally writer.close()
  }

  def makeTestCache(baseDir: File, repoName: String): Unit = {
    makeIndex(CacheProperties.indexFileForRepository(baseDir, repoName))(template1, nonLocalTypesafeTemplate)
    // Now we create our files:
    val dir = new File(baseDir, repoName)
    val templateDir = new File(dir, "ID-1")
    IO createDirectory templateDir
    IO.write(new File(templateDir, "build.sbt"), """name := "Test" """)
    val tutorialDir = new File(templateDir, Constants.TUTORIAL_DIR)
    IO createDirectory tutorialDir
    IO.write(new File(tutorialDir, "index.html"), "<html></html>")
    val cacheProps = new CacheProperties(new File(dir, Constants.CACHE_PROPS_FILENAME))
    cacheProps.cacheIndexHash = FIRST_INDEX_ID
    cacheProps.catalogName = repoName
    cacheProps.save()
  }
}
