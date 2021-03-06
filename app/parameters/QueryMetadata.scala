package parameters

import play.api.http.MimeTypes
import play.api.libs.json.{JsObject, Json}

class QueryMetadata(var nonDefaultJson: JsObject = Json.obj(), var fullJson: JsObject = Json.obj(), var mimeType: String = MimeTypes.JSON) {
  var longRunning: Boolean = false
  var key: Option[String] = None
  var estimatedDocumentsToProcess: Int = 0
  var estimatedNumberOfResults: Int = 0
  var documentsProcessed: Int = 0
  val startTime: Long = System.currentTimeMillis
  def elapsed: Long = System.currentTimeMillis - startTime
  def eta: Long = if (documentsProcessed == 0) -1 else (estimatedDocumentsToProcess - documentsProcessed) * elapsed / documentsProcessed
  def status: JsObject = Json.obj("startTime"->startTime, "estimatedNumberOfResults"->estimatedNumberOfResults,"elapsed"->elapsed, "estimatedDocumentsToProcess"->estimatedDocumentsToProcess,"documentsProcessed"->documentsProcessed,"eta"-> eta)
}
