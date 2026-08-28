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
  final String reviewerModel
  final double dispatcherTemperature
  final double architectTemperature
  final double engineerTemperature
  final double reviewerTemperature
  final long dispatcherTimeoutSeconds
  final long architectTimeoutSeconds
  final long engineerTimeoutSeconds
  final long reviewerTimeoutSeconds
  final boolean autoExecute

  TeamSettings(
    @Value('${assistant.team.enabled:false}') boolean enabled,
    @Value('${assistant.team.architect-model:${assistant.llm.model}}') String architectModel,
    @Value('${assistant.team.engineer-model:${assistant.llm.model}}') String engineerModel,
    @Value('${assistant.team.dispatcher-model:${assistant.llm.model}}') String dispatcherModel,
    @Value('${assistant.team.reviewer-model:${assistant.llm.model}}') String reviewerModel,
    @Value('${assistant.team.dispatcher-temperature:0.1}') double dispatcherTemperature,
    @Value('${assistant.team.architect-temperature:0.3}') double architectTemperature,
    @Value('${assistant.team.engineer-temperature:0.2}') double engineerTemperature,
    @Value('${assistant.team.reviewer-temperature:0.1}') double reviewerTemperature,
    @Value('${assistant.team.dispatcher-timeout-seconds:30}') long dispatcherTimeoutSeconds,
    @Value('${assistant.team.architect-timeout-seconds:300}') long architectTimeoutSeconds,
    @Value('${assistant.team.engineer-timeout-seconds:600}') long engineerTimeoutSeconds,
    @Value('${assistant.team.reviewer-timeout-seconds:300}') long reviewerTimeoutSeconds,
    @Value('${assistant.team.auto-execute:true}') boolean autoExecute
  ) {
    this.enabled = enabled
    this.architectModel = architectModel
    this.engineerModel = engineerModel
    this.dispatcherModel = dispatcherModel
    this.reviewerModel = reviewerModel
    this.dispatcherTemperature = dispatcherTemperature
    this.architectTemperature = architectTemperature
    this.engineerTemperature = engineerTemperature
    this.reviewerTemperature = reviewerTemperature
    this.dispatcherTimeoutSeconds = dispatcherTimeoutSeconds
    this.architectTimeoutSeconds = architectTimeoutSeconds
    this.engineerTimeoutSeconds = engineerTimeoutSeconds
    this.reviewerTimeoutSeconds = reviewerTimeoutSeconds
    this.autoExecute = autoExecute
  }
}
