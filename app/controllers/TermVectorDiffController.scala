package controllers

import java.util.function.LongConsumer
import javax.inject.{Inject, Singleton}

import com.koloboke.collect.set.hash.HashLongSets
import parameters._
import play.api.libs.json.{JsNull, Json}
import play.api.{Configuration, Environment}
import services.{Distance, IndexAccessProvider, TermVectors}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

@Singleton
class TermVectorDiffController @Inject() (implicit iap: IndexAccessProvider, env: Environment, conf: Configuration) extends AQueuingController(env, conf) {
  
  import TermVectors._
  
    // calculate distance between two term vectors across a metadata variable (use to create e.g. graphs of term meaning changes)
  def termVectorDiff(index: String) = Action { implicit request =>
    implicit val ia = iap(index)
    import ia._
    val p = request.body.asFormUrlEncoded.getOrElse(request.queryString)
    val gp = GeneralParameters()
    val grpp = GroupingParameters()
    val tvq1 = QueryParameters("t1_")
    val tvq2 = QueryParameters("t2_")
    val tvpl = LocalTermVectorProcessingParameters()
    val tvpa = AggregateTermVectorProcessingParameters()
    val meaningfulTerms: Int = p.get("meaningfulTerms").map(_.head.toInt).getOrElse(0)
    implicit val tlc = gp.tlc
    implicit val ec = gp.executionContext
    val qm = Json.obj("meaningfulTerms"->meaningfulTerms) ++ grpp.toJson ++ gp.toJson ++ tvq1.toJson ++ tvq2.toJson ++ tvpl.toJson ++ tvpa.toJson
    getOrCreateResult("termVectorDiff",ia.indexMetadata, qm, gp.force, gp.pretty, () => {
      val (qlevel1,termVector1Query) = buildFinalQueryRunningSubQueries(exactCounts = false, tvq1.requiredQuery)
      val (qlevel2,termVector2Query) = buildFinalQueryRunningSubQueries(exactCounts = false, tvq2.requiredQuery)
      val tvm1f = Future { getGroupedAggregateContextVectorsForQuery(ia.indexMetadata.levelMap(qlevel1),searcher(qlevel1, SumScaling.ABSOLUTE), termVector1Query,tvpl,extractContentTermsFromQuery(termVector1Query),grpp,tvpa,gp.maxDocs/2) }
      val tvm2f = Future { getGroupedAggregateContextVectorsForQuery(ia.indexMetadata.levelMap(qlevel2),searcher(qlevel2, SumScaling.ABSOLUTE), termVector2Query,tvpl,extractContentTermsFromQuery(termVector2Query),grpp,tvpa,gp.maxDocs/2) }
      val (_,tvm1) = Await.result(tvm1f, Duration.Inf)
      val (_,tvm2) = Await.result(tvm2f, Duration.Inf)
      val obj = (tvm1.keySet ++ tvm2.keySet).map(key => {
        var map = Json.obj("attrs"->Json.toJson(grpp.attrs.zip(key).toMap),
            "distance"->(if (!tvm1.contains(key) || !tvm2.contains(key)) JsNull else {
              val distance = tvpa.distance(tvm1(key).cv,tvm2(key).cv)
              if (distance.isNaN) JsNull else Json.toJson(distance)
            }), 
            "df1"->Json.toJson(tvm1.get(key).map(_.docFreq).getOrElse(0l)),"df2"->Json.toJson(tvm2.get(key).map(_.docFreq).getOrElse(0l)),"tf1"->Json.toJson(tvm1.get(key).map(_.totalTermFreq).getOrElse(0l)),"tf2"->Json.toJson(tvm2.get(key).map(_.totalTermFreq).getOrElse(0l)))
        if (tvm1.contains(key) && tvm2.contains(key) && meaningfulTerms!=0) {
          val tv1 = tvm1(key).cv
          val tv2 = tvm2(key).cv
          Distance.normalize(tv1)
          Distance.normalize(tv2)
          val maxHeap = mutable.PriorityQueue.empty[(Long,Double)]((x: (Long, Double), y: (Long, Double)) => y._2 compare x._2)
          val maxHeap1 = mutable.PriorityQueue.empty[(Long,Double)]((x: (Long, Double), y: (Long, Double)) => y._2 compare x._2)
          val maxHeap2 = mutable.PriorityQueue.empty[(Long,Double)]((x: (Long, Double), y: (Long, Double)) => y._2 compare x._2)
          val minHeap = mutable.PriorityQueue.empty[(Long,Double)]((x: (Long, Double), y: (Long, Double)) => x._2 compare y._2)
          var total = 0
          HashLongSets.newImmutableSet(tv1.keySet, tv2.keySet).forEach(new LongConsumer() {
            override def accept(term: Long): Unit = {
              val diff = tv1.getOrDefault(term, 0.0)-tv2.getOrDefault(term, 0.0)
              val adiff = math.abs(diff)
              total+=1
              if (total<=meaningfulTerms) { 
                maxHeap += ((term,adiff))
                maxHeap1 += ((term,diff))
                maxHeap2 += ((term,-diff))
                minHeap += ((term,adiff))
              } else {
                if (maxHeap.head._2 < adiff) {
                  maxHeap.dequeue()
                  maxHeap += ((term,adiff))
                }
                if (maxHeap1.head._2 < diff) {
                  maxHeap1.dequeue()
                  maxHeap1 += ((term,diff))
                }
                if (maxHeap2.head._2 < -diff) {
                  maxHeap2.dequeue()
                  maxHeap2 += ((term,-diff))
                }
                if (minHeap.head._2 > adiff) {
                  minHeap.dequeue()
                  minHeap += ((term,adiff))
                }
              }
            }
          })
          val ir = reader(qlevel1)
          map = map + ("mostDifferentTerms"->Json.toJson(maxHeap.map(p => (termOrdToTerm(ir, p._1),p._2)).toMap))
          map = map + ("mostDistinctiveTermsForTerm1"->Json.toJson(maxHeap1.map(p => (termOrdToTerm(ir, p._1),p._2)).toMap))
          map = map + ("mostDistinctiveTermsForTerm2"->Json.toJson(maxHeap2.map(p => (termOrdToTerm(ir, p._1),p._2)).toMap))
          map = map + ("mostSimilarTerms"->Json.toJson(minHeap.map(p => (termOrdToTerm(ir, p._1),p._2)).toMap))
        }
        map
      })
      Json.toJson(obj)
    })
  }
}