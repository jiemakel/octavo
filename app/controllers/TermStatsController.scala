package controllers

import javax.inject.Singleton
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import play.api.libs.json.JsValue
import org.apache.lucene.search.SimpleCollector
import org.apache.lucene.search.Scorer
import org.apache.lucene.index.LeafReaderContext
import play.api.libs.json.Json
import play.api.mvc.Action
import javax.inject.Inject
import org.apache.lucene.queryparser.classic.QueryParser
import play.api.mvc.Controller
import javax.inject.Named
import services.IndexAccess
import parameters.SumScaling
import play.api.libs.json.JsObject
import parameters.QueryParameters
import akka.stream.Materializer
import play.api.Environment
import parameters.GeneralParameters
import scala.collection.mutable.HashMap

@Singleton
class TermStatsController @Inject() (implicit ia: IndexAccess, env: Environment) extends AQueuingController(env) {
  import ia._
  
  class Stats {
    var termFreqs = new ArrayBuffer[Int]
    var totalTermFreq = 0l
    var docFreq = 0l
    def toJson = 
      if (!termFreqs.isEmpty) Json.obj("termFreqs"->termFreqs.sorted,"totalTermFreq"->totalTermFreq,"docFreq"->docFreq)
      else Json.obj("totalTermFreq"->totalTermFreq,"docFreq"->docFreq)
  }
  
  private def getStats(is: IndexSearcher, q: Query, attrO: Option[String], attrLength: Int, gatherTermFreqsPerDoc: Boolean): JsValue = {
    if (attrO.isDefined) {
      val attr = attrO.get
      var attrGetter: (Int) => String = null
      val groupedStats = new HashMap[String,Stats]
      is.search(q, new SimpleCollector() {
        override def needsScores: Boolean = true
        
        override def setScorer(scorer: Scorer) = this.scorer = scorer
  
        var scorer: Scorer = null
  
        override def collect(doc: Int) {
          val cattr = attrGetter(doc)
          val s = groupedStats.getOrElseUpdate(if (attrLength == -1) cattr else cattr.substring(0,attrLength), new Stats)
          s.docFreq += 1
          val score = scorer.score().toInt
          if (gatherTermFreqsPerDoc) s.termFreqs += score
          s.totalTermFreq += score
        }
        
        override def doSetNextReader(context: LeafReaderContext) = {
          attrGetter = ia.indexMetadata.getter(context.reader, attr).andThen(_.iterator.next)
        }
      })
      Json.arr(groupedStats.toIterable.map(p => Json.obj("attr"->p._1,"stats"->p._2.toJson)))
    } else {
      val s = new Stats
      is.search(q, new SimpleCollector() {
        override def needsScores: Boolean = true
        
        override def setScorer(scorer: Scorer) = this.scorer = scorer
  
        var scorer: Scorer = null
  
        override def collect(doc: Int) {
          s.docFreq += 1
          val score = scorer.score().toInt
          if (gatherTermFreqsPerDoc) s.termFreqs += score
          s.totalTermFreq += score
        }
        
      })
      s.toJson
    }
  }
  
  def stats() = Action { implicit request =>
    val p = request.body.asFormUrlEncoded.getOrElse(request.queryString)
    val gp = new GeneralParameters
    val q = new QueryParameters
    val gatherTermFreqsPerDoc = p.get("termFreqs").exists(v => v(0)=="" || v(0).toBoolean)
    val attr = p.get("attr").map(_(0))
    val attrLength = p.get("attrLength").map(_(0).toInt).getOrElse(-1)
    val qm = Json.obj("method"->"termStats","attr"->attr,"attrLength"->attrLength) ++ gp.toJson ++ q.toJson
    implicit val ec = gp.executionContext
    getOrCreateResult(qm, gp.force, gp.pretty, () => {
      implicit val tlc = gp.tlc
      val (qlevel,query) = buildFinalQueryRunningSubQueries(q.requiredQuery)
      getStats(searcher(qlevel, SumScaling.ABSOLUTE), query, attr, attrLength, gatherTermFreqsPerDoc)
    })
  }  
}