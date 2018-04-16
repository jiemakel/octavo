package parameters

import controllers.RunScriptController
import groovy.lang.GroovyShell
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, Request}
import services.IndexAccess

class GroupingParameters(prefix: String = "", suffix: String = "")(implicit request: Request[AnyContent], ia: IndexAccess, queryMetadata: QueryMetadata) {
  private val p = request.body.asFormUrlEncoded.getOrElse(request.queryString)
  val fields = p.getOrElse("field", Seq.empty)
  val fieldLengths = p.getOrElse("fieldLength", Seq.empty).map(_.toInt)
  private val fieldTransformerS = p.get("fieldTransformer").map(_.head)
  val fieldTransformer = fieldTransformerS.map(apScript => new GroovyShell(RunScriptController.compilerConfiguration).parse(apScript))
  private val grouperS = p.get("grouper").map(_.head)
  val grouper = grouperS.map(apScript => new GroovyShell(RunScriptController.compilerConfiguration).parse(apScript))
  def toJson = Json.obj(prefix+"fields"+suffix->fields,prefix+"fieldLengths"+suffix->fieldLengths,prefix+"fieldTransformer"+suffix->fieldTransformerS,prefix+"grouper"+suffix->grouperS)
  def isDefined = !fields.isEmpty || grouper.isDefined
  queryMetadata.json = queryMetadata.json ++ toJson
}

