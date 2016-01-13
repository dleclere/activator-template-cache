/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package templates
package repository

import java.net.URI
import cache.Constants

// This class gives us the basic layout of a repository.
object Layout {
  val CURRENT_INDEX_TAG_FILE = "current.properties"
  val INDEXES_DIRECTORY_NAME = "index"
  val TEMPLATES_DIRECTORY_NAME = "templates"

  private implicit class FancyUri(val u: URI) extends AnyVal {
    def /(moreUri: String): URI =
      new EnhancedURI(u).appendToPath(moreUri)
  }

  private def versionNumber(version: String): String =
    s"v${version}"
  private def indexFile(hash: String): String =
    s"index-${hash}.zip"
  private def templateFile(id: String): String =
    s"${id}.zip"

  // Creates our hash directories to ease browsing these directories.
  def hashDirectories(id: String): String = {
    val firstBit = id take 2
    val secondBit = id take 6
    firstBit + "/" + secondBit + "/"
  }

  def indexDirectory(baseRepo: URI): URI =
    baseRepo / INDEXES_DIRECTORY_NAME / versionNumber(Constants.INDEX_REPOSITORY_GENERATION)
  def currentIndexTagUri(baseRepo: URI): URI =
    indexDirectory(baseRepo) / CURRENT_INDEX_TAG_FILE
  def indexUri(baseRepo: URI, hash: String): URI =
    indexDirectory(baseRepo) / indexFile(hash)
  def templateDirectory(base: URI): URI =
    base / TEMPLATES_DIRECTORY_NAME
  def templateFile(base: URI, id: String): URI =
    templateDirectory(base) / (hashDirectories(id) + templateFile(id))
  def templateBundle(base: URI, activatorVersion: String, id: String, templateName: String): URI =
    templateDirectory(base) / (hashDirectories(id) + id + "/" + activatorVersion + "/" + templateName + ".zip")
  def authorLogo(base: URI, id: String): URI =
    templateDirectory(base) / (hashDirectories(id) + id + "/logo")
  def minimalActivatorDist(base: URI, activatorVersion: String): URI =
    base / s"${activatorVersion}/typesafe-activator-${activatorVersion}-minimal.zip"
}

// Helper to pull down the layout all at once...
class Layout(base: URI) {
  val currentIndexTag = Layout.currentIndexTagUri(base)
  println("current index tag: " + currentIndexTag)
  def template(id: String) = Layout.templateFile(base, id)
  def index(hash: String) = Layout.indexUri(base, hash)
  def templateBundle(activatorVersion: String, id: String, templateName: String) =
    Layout.templateBundle(base, activatorVersion, id, templateName)
  def authorLogo(id: String) =
    Layout.authorLogo(base, id)
  def minimalActivatorDist(activatorVersion: String) =
    Layout.minimalActivatorDist(base, activatorVersion)
}
