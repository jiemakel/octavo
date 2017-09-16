package controllers

import javax.inject.Singleton
import javax.inject.Inject
import services.IndexAccess
import akka.stream.Materializer
import play.api.Environment
import parameters.GeneralParameters
import play.api.mvc.Action
import parameters.QueryParameters
import parameters.AggregateTermVectorProcessingParameters
import parameters.LocalTermVectorProcessingParameters
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import play.api.libs.json.Json
import parameters.SumScaling
import org.apache.lucene.search.BooleanClause.Occur
import services.TermVectors
import services.IndexAccessProvider
import play.api.Configuration
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import com.koloboke.function.LongDoubleConsumer
import scala.collection.parallel.ParIterable
import scala.concurrent.ExecutionContext
import scala.collection.parallel.TaskSupport
import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.ParSeq

@Singleton
class CollocationsController @Inject() (implicit iap: IndexAccessProvider, env: Environment, conf: Configuration) extends AQueuingController(env, conf) {
  
  import TermVectors._
  
  // get collocations for a term query (+ a possible limit query), for defining a topic
  def collocations(index: String) = Action { implicit request =>
    implicit val ia = iap(index)
    import ia._
    val p = request.body.asFormUrlEncoded.getOrElse(request.queryString)
    val gp = GeneralParameters()
    val termVectorQueryParameters = QueryParameters()
    val termVectorLocalProcessingParameters = LocalTermVectorProcessingParameters()
    val termVectorAggregateProcessingParameters = AggregateTermVectorProcessingParameters()
/*    val comparisonTermVectorQueryParameters = QueryParameters("c_")
    val comparisonTermVectorLocalProcessingParameters = LocalTermVectorProcessingParameters("c_")
    val comparisonTermVectorAggregateProcessingParameters = AggregateTermVectorProcessingParameters("c_") */
    val resultTermVectorLimitQueryParameters = QueryParameters("r_")
    val resultTermVectorLocalProcessingParameters = LocalTermVectorProcessingParameters("r_")
    val resultTermVectorAggregateProcessingParameters = AggregateTermVectorProcessingParameters("r_")
    val termVectors = p.get("termVectors").exists(v => v(0)=="" || v(0).toBoolean)
    val distances = p.get("distances").exists(v => v(0)=="" || v(0).toBoolean)
    val qm = Json.obj("distances"->distances,"termVectors"->termVectors) ++ gp.toJson ++ termVectorQueryParameters.toJson ++ termVectorLocalProcessingParameters.toJson ++ termVectorAggregateProcessingParameters.toJson ++ resultTermVectorLimitQueryParameters.toJson ++ resultTermVectorLocalProcessingParameters.toJson ++ resultTermVectorAggregateProcessingParameters.toJson
    implicit val iec = gp.executionContext
    getOrCreateResult("collocations",ia.indexMetadata, qm, gp.force, gp.pretty, () => {
      implicit val tlc = gp.tlc
      implicit val its = gp.taskSupport
      implicit val ifjp = gp.forkJoinPool
      val (qlevel,termVectorQuery) = buildFinalQueryRunningSubQueries(false, termVectorQueryParameters.requiredQuery)
      val is = searcher(qlevel, SumScaling.ABSOLUTE)
      val ir = is.getIndexReader
      val maxDocs = if (gp.maxDocs == -1 || termVectorAggregateProcessingParameters.limit == -1) -1 else if (termVectors) gp.maxDocs / (termVectorAggregateProcessingParameters.limit + 1) else gp.maxDocs / 2
      val (md, collocationsMap) = getAggregateContextVectorForQuery(is, termVectorQuery,termVectorLocalProcessingParameters,extractContentTermsFromQuery(termVectorQuery),termVectorAggregateProcessingParameters,maxDocs)
      val collocations = new ArrayBuffer[(Long,Double)]
      limitTermVector(collocationsMap,termVectorAggregateProcessingParameters).forEach(new LongDoubleConsumer {
         override def accept(k: Long, v: Double) {
           collocations += ((k,v))
         }
      })
      Json.obj("metadata"->md.toJson,"terms"->(if (resultTermVectorAggregateProcessingParameters.dimensions>0) {
        val resultLimitQuery = resultTermVectorLimitQueryParameters.query.map(buildFinalQueryRunningSubQueries(false,_)._2)
        val ctermVectors = toParallel(collocations).map{term => 
          val termS = termOrdToTerm(ir,term._1)
          val bqb = new BooleanQuery.Builder().add(new TermQuery(new Term(indexMetadata.contentField,termS)), Occur.FILTER)
          for (q <- resultLimitQuery) bqb.add(q, Occur.FILTER)
          getAggregateContextVectorForQuery(is, bqb.build(), resultTermVectorLocalProcessingParameters, Seq(termS), resultTermVectorAggregateProcessingParameters, maxDocs)
        }.seq
        val mdsMatrix = resultTermVectorAggregateProcessingParameters.dimensionalityReduction(ctermVectors.map(p => limitTermVector(p._2,resultTermVectorAggregateProcessingParameters)),resultTermVectorAggregateProcessingParameters)
        collocations.zipWithIndex.toSeq.sortBy(-_._1._2).map{ case ((term,weight),i) =>
          val r = Json.obj("term"->termOrdToTerm(ir, term), "termVector"->Json.obj("metadata"->ctermVectors(i)._1.toJson,"terms"->mdsMatrix(i)), "weight"->weight)
          if (distances) 
            r ++ Json.obj("distance"->termVectorAggregateProcessingParameters.distance(collocationsMap,ctermVectors(i)._2))
          else r
        }
      } else if (termVectors) {
        val resultLimitQuery = resultTermVectorLimitQueryParameters.query.map(buildFinalQueryRunningSubQueries(false,_)._2)
        toParallel(collocations.sortBy(-_._2)).map{ case (term, weight) =>
          val termS = termOrdToTerm(ir,term)
          val bqb = new BooleanQuery.Builder().add(new TermQuery(new Term(indexMetadata.contentField,termS)), Occur.FILTER)
          for (q <- resultLimitQuery) bqb.add(q, Occur.FILTER)
          val (md, ctermVector) = getAggregateContextVectorForQuery(is, bqb.build(), resultTermVectorLocalProcessingParameters, Seq(termS), resultTermVectorAggregateProcessingParameters, maxDocs) 
          val r = Json.obj("term" -> termS, "termVector"->Json.obj("metadata"->md.toJson, "terms"->termOrdMapToOrderedTermSeq(ir, limitTermVector(ctermVector,resultTermVectorAggregateProcessingParameters)).map(p=>Json.obj("term" -> p._1, "weight" -> p._2)),"weight"->weight))
          if (distances) 
            r ++ Json.obj("distance"->termVectorAggregateProcessingParameters.distance(collocationsMap,ctermVector))
          else r
        }.seq
      } else if (distances) {
        val resultLimitQuery = resultTermVectorLimitQueryParameters.query.map(buildFinalQueryRunningSubQueries(false,_)._2)
        toParallel(collocations.sortBy(-_._2)).map{ case (term, weight) =>
          val resultLimitQuery = resultTermVectorLimitQueryParameters.query.map(buildFinalQueryRunningSubQueries(false,_)._2)
          val termS = termOrdToTerm(ir,term)
          val bqb = new BooleanQuery.Builder().add(new TermQuery(new Term(indexMetadata.contentField,termS)), Occur.FILTER)
          for (q <- resultLimitQuery) bqb.add(q, Occur.FILTER)
          val (md, ctermVector) = getAggregateContextVectorForQuery(is, bqb.build(), resultTermVectorLocalProcessingParameters, Seq(termS), resultTermVectorAggregateProcessingParameters, maxDocs)
          Json.obj("term" -> termS,"weight"->weight,"distance"->termVectorAggregateProcessingParameters.distance(collocationsMap,ctermVector))
        }.seq  
      } else collocations.sortBy(-_._2).map(p => Json.obj("term"->termOrdToTerm(ir,p._1), "weight" -> p._2))))
    })
  }
}