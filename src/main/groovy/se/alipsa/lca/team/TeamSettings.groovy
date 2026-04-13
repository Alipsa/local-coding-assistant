package se.alipsa.lca.team

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
@CompileStatic
class TeamSettings {

  final boolean enabled
  final String architectModel
  final String engineerModel
  final String dispatcherModel
  final double dispatcherTemperature
  final boolean autoExecute

  TeamSettings(
    @Value('${assistant.team.enabled:false}') boolean enabled,
    @Value('${assistant.team.architect-model:${assistant.llm.model:qwen-coder-96k:latest}}') String architectModel,
    @Value('${assistant.team.engineer-model:${assistant.llm.model:qwen-coder-96k:latest}}') String engineerModel,
    @Value('${assistant.team.dispatcher-model:${assistant.llm.model:qwen-coder-96k:latest}}') String dispatcherModel,
    @Value('${assistant.team.dispatcher-temperature:0.1}') double dispatcherTemperature,
    @Value('${assistant.team.auto-execute:true}') boolean autoExecute
  ) {
    this.enabled = enabled
    this.architectModel = architectModel
    this.engineerModel = engineerModel
    this.dispatcherModel = dispatcherModel
    this.dispatcherTemperature = dispatcherTemperature
    this.autoExecute = autoExecute
  }
}
