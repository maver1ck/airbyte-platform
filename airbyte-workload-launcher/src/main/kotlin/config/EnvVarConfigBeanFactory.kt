/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.launcher.config

import io.airbyte.commons.constants.WorkerConstants
import io.airbyte.commons.features.EnvVariableFeatureFlags
import io.airbyte.commons.features.FeatureFlags
import io.airbyte.commons.workers.config.WorkerConfigs
import io.airbyte.config.Configs
import io.airbyte.config.EnvConfigs
import io.airbyte.config.storage.StorageConfig
import io.airbyte.workers.process.Metadata.AWS_ACCESS_KEY_ID
import io.airbyte.workers.process.Metadata.AWS_SECRET_ACCESS_KEY
import io.airbyte.workers.sync.OrchestratorConstants
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarSource
import io.fabric8.kubernetes.api.model.SecretKeySelector
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Value
import io.micronaut.context.env.Environment
import io.micronaut.core.util.StringUtils
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.util.function.Consumer
import io.airbyte.commons.envvar.EnvVar as AbEnvVar

private val logger = KotlinLogging.logger {}

/**
 * Provides and configures the environment variables for the containers we launch.
 */
@Factory
class EnvVarConfigBeanFactory {
  /**
   * Map of env vars to be passed to the orchestrator container.
   */
  @Singleton
  @Named("orchestratorEnvMap")
  fun orchestratorEnvMap(
    featureFlags: FeatureFlags,
    workerEnv: Configs.WorkerEnvironment,
    storageConfig: StorageConfig,
    @Named("workloadApiEnvMap") workloadApiEnvMap: Map<String, String>,
    @Named("metricsEnvMap") metricsEnvMap: Map<String, String>,
    @Named("micronautEnvMap") micronautEnvMap: Map<String, String>,
    @Named("apiClientEnvMap") apiClientEnvMap: Map<String, String>,
    @Value("\${airbyte.container.orchestrator.java-opts}") containerOrchestratorJavaOpts: String,
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.secret-name}") awsAssumedRoleSecretName: String,
  ): Map<String, String> {
    // Build the map of additional environment variables to be passed to the container orchestrator
    val envMap: MutableMap<String, String> = HashMap()
    envMap[EnvVariableFeatureFlags.AUTO_DETECT_SCHEMA] = java.lang.Boolean.toString(featureFlags.autoDetectSchema())
    envMap[EnvVariableFeatureFlags.APPLY_FIELD_SELECTION] = java.lang.Boolean.toString(featureFlags.applyFieldSelection())
    envMap[EnvVariableFeatureFlags.FIELD_SELECTION_WORKSPACES] = featureFlags.fieldSelectionWorkspaces()
    envMap[JAVA_OPTS_ENV_VAR] = containerOrchestratorJavaOpts

    val configs: Configs = EnvConfigs()
    envMap[AbEnvVar.FEATURE_FLAG_CLIENT.name] = AbEnvVar.FEATURE_FLAG_CLIENT.fetch() ?: ""
    envMap[AbEnvVar.LAUNCHDARKLY_KEY.name] = AbEnvVar.LAUNCHDARKLY_KEY.fetch() ?: ""
    envMap[AbEnvVar.OTEL_COLLECTOR_ENDPOINT.name] = AbEnvVar.OTEL_COLLECTOR_ENDPOINT.fetch() ?: ""
    envMap[AbEnvVar.SOCAT_KUBE_CPU_LIMIT.name] = configs.socatSidecarKubeCpuLimit
    envMap[AbEnvVar.SOCAT_KUBE_CPU_REQUEST.name] = configs.socatSidecarKubeCpuRequest

    // secret name used by orchestrator for assumed role look-ups
    envMap[AbEnvVar.AWS_ASSUME_ROLE_SECRET_NAME.name] = awsAssumedRoleSecretName

    // Manually add the worker environment
    envMap[WorkerConstants.WORKER_ENVIRONMENT] = workerEnv.name

    // Cloud storage config
    envMap.putAll(storageConfig.toEnvVarMap())

    // Workload Api configuration
    envMap.putAll(workloadApiEnvMap)

    // Api client configuration
    envMap.putAll(apiClientEnvMap)

    // Metrics configuration
    envMap.putAll(metricsEnvMap)

    // Micronaut environment
    envMap.putAll(micronautEnvMap)

    // TODO: Don't do this. Be explicit about what env vars we pass.
    // Copy over all local values
    val localEnvMap =
      System.getenv()
        .filter { OrchestratorConstants.ENV_VARS_TO_TRANSFER.contains(it.key) }

    envMap.putAll(localEnvMap)

    return envMap
  }

  /**
   * The list of environment variables to be passed to the orchestrator.
   * The created list contains both regular environment variables and environment variables that
   * are sourced from Kubernetes secrets.
   */
  @Singleton
  @Named("orchestratorEnvVars")
  fun orchestratorEnvVars(
    @Named("orchestratorEnvMap") envMap: Map<String, String>,
    @Named("orchestratorSecretsEnvMap") secretsEnvMap: Map<String, EnvVarSource>,
  ): List<EnvVar> {
    val secretEnvVars =
      secretsEnvMap
        .map { EnvVar(it.key, null, it.value) }
        .toList()

    val envVars =
      envMap
        .filterNot { env ->
          secretsEnvMap.containsKey(env.key)
            .also {
              if (it) {
                logger.info { "Skipping env-var ${env.key} as it was already defined as a secret. " }
              }
            }
        }
        .map { EnvVar(it.key, it.value, null) }
        .toList()

    return envVars + secretEnvVars
  }

  /**
   * The list of env vars to be passed to the check sidecar container.
   */
  @Singleton
  @Named("sideCarEnvVars")
  fun sideCarEnvVars(
    storageConfig: StorageConfig,
    @Named("workloadApiEnvMap") workloadApiEnvMap: Map<String, String>,
    @Named("apiClientEnvMap") apiClientEnvMap: Map<String, String>,
    @Named("micronautEnvMap") micronautEnvMap: Map<String, String>,
    @Named("workloadApiSecretEnv") secretsEnvMap: Map<String, EnvVarSource>,
  ): List<EnvVar> {
    val envMap: MutableMap<String, String> = HashMap()

    // Cloud storage configuration
    envMap.putAll(storageConfig.toEnvVarMap())

    // Workload Api configuration
    envMap.putAll(workloadApiEnvMap)

    // Api client configuration
    envMap.putAll(apiClientEnvMap)

    // Micronaut environment
    envMap.putAll(micronautEnvMap)

    val envVars =
      envMap
        .map { EnvVar(it.key, it.value, null) }
        .toList()

    val secretEnvVars =
      secretsEnvMap
        .map { EnvVar(it.key, null, it.value) }
        .toList()

    return envVars + secretEnvVars
  }

  /**
   * The list of env vars to be passed to the connector container we are checking.
   */
  @Singleton
  @Named("checkEnvVars")
  fun checkEnvVars(
    @Named("checkWorkerConfigs") workerConfigs: WorkerConfigs,
  ): List<EnvVar> {
    return workerConfigs.envMap
      .map { EnvVar(it.key, it.value, null) }
      .toList()
  }

  /**
   * The list of env vars to be passed to the connector container we are discovering.
   */
  @Singleton
  @Named("discoverEnvVars")
  fun discoverEnvVars(
    @Named("discoverWorkerConfigs") workerConfigs: WorkerConfigs,
  ): List<EnvVar> {
    return workerConfigs.envMap
      .map { EnvVar(it.key, it.value, null) }
      .toList()
  }

  /**
   * The list of env vars to be passed to the connector container we are specifying.
   */
  @Singleton
  @Named("specEnvVars")
  fun specEnvVars(
    @Named("specWorkerConfigs") workerConfigs: WorkerConfigs,
  ): List<EnvVar> {
    return workerConfigs.envMap
      .map { EnvVar(it.key, it.value, null) }
      .toList()
  }

  @Singleton
  @Named("workloadApiSecretEnv")
  fun workloadApiSecretEnv(
    @Value("\${airbyte.workload-api.bearer-token-secret-name}") bearerTokenSecretName: String,
    @Value("\${airbyte.workload-api.bearer-token-secret-key}") bearerTokenSecretKey: String,
  ): Map<String, EnvVarSource> {
    return buildMap {
      if (bearerTokenSecretName.isNotBlank()) {
        put(WORKLOAD_API_BEARER_TOKEN_ENV_VAR, createEnvVarSource(bearerTokenSecretName, bearerTokenSecretKey))
      }
    }
  }

  /**
   * To be injected into orchestrator pods, so they can start AWS connector pods that use assumed role access.
   */
  @Singleton
  @Named("orchestratorAwsAssumedRoleSecretEnv")
  fun orchestratorAwsAssumedRoleSecretEnv(
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.access-key}") awsAssumedRoleAccessKey: String,
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.secret-key}") awsAssumedRoleSecretKey: String,
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.secret-name}") awsAssumedRoleSecretName: String,
  ): Map<String, EnvVarSource> {
    return buildMap {
      if (awsAssumedRoleSecretName.isNotBlank()) {
        put(AWS_ASSUME_ROLE_ACCESS_KEY_ID_ENV_VAR, createEnvVarSource(awsAssumedRoleSecretName, awsAssumedRoleAccessKey))
        put(AWS_ASSUME_ROLE_SECRET_ACCESS_KEY_ENV_VAR, createEnvVarSource(awsAssumedRoleSecretName, awsAssumedRoleSecretKey))
      }
    }
  }

  /**
   * To be injected into AWS connector pods that use assumed role access.
   */
  @Singleton
  @Named("connectorAwsAssumedRoleSecretEnv")
  fun connectorAwsAssumedRoleSecretEnv(
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.access-key}") accessKey: String,
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.secret-key}") secretKey: String,
    @Value("\${airbyte.connector.source.credentials.aws.assumed-role.secret-name}") secretName: String,
  ): List<EnvVar> {
    return buildList {
      if (secretName.isNotBlank()) {
        add(EnvVar(AWS_ACCESS_KEY_ID, null, createEnvVarSource(secretName, accessKey)))
        add(EnvVar(AWS_SECRET_ACCESS_KEY, null, createEnvVarSource(secretName, secretKey)))
      }
    }
  }

  /**
   * Creates a map that represents environment variables that will be used by the orchestrator that are sourced from kubernetes secrets.
   * The map key is the environment variable name and the map value contains the kubernetes secret name and key
   */
  @Singleton
  @Named("orchestratorSecretsEnvMap")
  fun orchestratorSecretsEnvMap(
    @Named("workloadApiSecretEnv") workloadApiSecretEnv: Map<String, EnvVarSource>,
    @Named("orchestratorAwsAssumedRoleSecretEnv") orchestratorAwsAssumedRoleSecretEnv: Map<String, EnvVarSource>,
  ): Map<String, EnvVarSource> {
    return workloadApiSecretEnv + orchestratorAwsAssumedRoleSecretEnv
  }

  private fun createEnvVarSource(
    secretName: String,
    secretKey: String,
  ): EnvVarSource {
    val secretKeySelector =
      SecretKeySelector().apply {
        name = secretName
        key = secretKey
      }

    val envVarSource =
      EnvVarSource().apply {
        secretKeyRef = secretKeySelector
      }

    return envVarSource
  }

  /**
   * Map of env vars for AirbyteApiClient
   */
  @Singleton
  @Named("apiClientEnvMap")
  fun apiClientEnvMap(
    @Value("\${airbyte.internal.api.host}") apiHost: String,
    @Value("\${airbyte.internal.api.auth-header.name}") apiAuthHeaderName: String,
    @Value("\${airbyte.internal.api.auth-header.value}") apiAuthHeaderValue: String,
    @Value("\${airbyte.control.plane.auth-endpoint}") controlPlaneAuthEndpoint: String,
    @Value("\${airbyte.data.plane.service-account.email}") dataPlaneServiceAccountEmail: String,
    @Value("\${airbyte.data.plane.service-account.credentials-path}") dataPlaneServiceAccountCredentialsPath: String,
    @Value("\${airbyte.acceptance.test.enabled}") isInTestMode: Boolean,
  ): Map<String, String> {
    val envMap: MutableMap<String, String> = HashMap()

    envMap[INTERNAL_API_HOST_ENV_VAR] = apiHost
    envMap[AIRBYTE_API_AUTH_HEADER_NAME_ENV_VAR] = apiAuthHeaderName
    envMap[AIRBYTE_API_AUTH_HEADER_VALUE_ENV_VAR] = apiAuthHeaderValue
    envMap[CONTROL_PLANE_AUTH_ENDPOINT_ENV_VAR] = controlPlaneAuthEndpoint
    envMap[DATA_PLANE_SERVICE_ACCOUNT_EMAIL_ENV_VAR] = dataPlaneServiceAccountEmail
    envMap[DATA_PLANE_SERVICE_ACCOUNT_CREDENTIALS_PATH_ENV_VAR] = dataPlaneServiceAccountCredentialsPath
    envMap[ACCEPTANCE_TEST_ENABLED_VAR] = java.lang.Boolean.toString(isInTestMode)

    return envMap
  }

  /**
   * Map of env vars for specifying the Micronaut environment.
   */
  @Singleton
  @Named("micronautEnvMap")
  fun micronautEnvMap(): Map<String, String> {
    val envMap: MutableMap<String, String> = HashMap()

    if (System.getenv(Environment.ENVIRONMENTS_ENV) != null) {
      envMap[Environment.ENVIRONMENTS_ENV] = "$WORKER_V2_MICRONAUT_ENV,${System.getenv(Environment.ENVIRONMENTS_ENV)}"
    } else {
      envMap[Environment.ENVIRONMENTS_ENV] = WORKER_V2_MICRONAUT_ENV
    }

    return envMap
  }

  /**
   * Map of env vars for configuring datadog and metrics publishing.
   */
  @Singleton
  @Named("metricsEnvMap")
  fun metricsEnvMap(
    @Value("\${datadog.agent.host}") dataDogAgentHost: String,
    @Value("\${datadog.agent.port}") dataDogStatsdPort: String,
    @Value("\${airbyte.metric.client}") metricClient: String,
    @Value("\${airbyte.metric.should-publish}") shouldPublishMetrics: String,
    @Value("\${datadog.orchestrator.disabled.integrations}") disabledIntegrations: String,
  ): Map<String, String> {
    val envMap: MutableMap<String, String> = HashMap()
    envMap[METRIC_CLIENT_ENV_VAR] = metricClient
    envMap[DD_AGENT_HOST_ENV_VAR] = dataDogAgentHost
    envMap[DD_SERVICE_ENV_VAR] = "airbyte-container-orchestrator"
    envMap[DD_DOGSTATSD_PORT_ENV_VAR] = dataDogStatsdPort
    envMap[PUBLISH_METRICS_ENV_VAR] = shouldPublishMetrics
    if (System.getenv(DD_ENV_ENV_VAR) != null) {
      envMap[DD_ENV_ENV_VAR] = System.getenv(DD_ENV_ENV_VAR)
    }
    if (System.getenv(DD_VERSION_ENV_VAR) != null) {
      envMap[DD_VERSION_ENV_VAR] = System.getenv(DD_VERSION_ENV_VAR)
    }

    // Disable DD agent integrations based on the configuration
    if (StringUtils.isNotEmpty(disabledIntegrations)) {
      listOf(*disabledIntegrations.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
        .forEach(
          Consumer { e: String ->
            envMap[String.format(DD_INTEGRATION_ENV_VAR_FORMAT, e.trim { it <= ' ' })] = java.lang.Boolean.FALSE.toString()
          },
        )
    }

    return envMap
  }

  /**
   * Map of env vars for configuring the WorkloadApiClient (separate from ApiClient).
   */
  @Singleton
  @Named("workloadApiEnvMap")
  fun workloadApiEnvVars(
    @Value("\${airbyte.workload-api.connect-timeout-seconds}") workloadApiConnectTimeoutSeconds: String,
    @Value("\${airbyte.workload-api.read-timeout-seconds}") workloadApiReadTimeoutSeconds: String,
    @Value("\${airbyte.workload-api.retries.delay-seconds}") workloadApiRetriesDelaySeconds: String,
    @Value("\${airbyte.workload-api.retries.max}") workloadApiRetriesMax: String,
    @Value("\${airbyte.workload-api.base-path}") workloadApiBasePath: String,
  ): Map<String, String> {
    val envMap: MutableMap<String, String> = HashMap()
    envMap[WORKLOAD_API_HOST_ENV_VAR] = workloadApiBasePath
    envMap[WORKLOAD_API_CONNECT_TIMEOUT_SECONDS_ENV_VAR] = workloadApiConnectTimeoutSeconds
    envMap[WORKLOAD_API_READ_TIMEOUT_SECONDS_ENV_VAR] = workloadApiReadTimeoutSeconds
    envMap[WORKLOAD_API_RETRY_DELAY_SECONDS_ENV_VAR] = workloadApiRetriesDelaySeconds
    envMap[WORKLOAD_API_MAX_RETRIES_ENV_VAR] = workloadApiRetriesMax

    return envMap
  }

  companion object {
    private const val METRIC_CLIENT_ENV_VAR = "METRIC_CLIENT"
    private const val DD_AGENT_HOST_ENV_VAR = "DD_AGENT_HOST"
    private const val DD_DOGSTATSD_PORT_ENV_VAR = "DD_DOGSTATSD_PORT"
    private const val DD_ENV_ENV_VAR = "DD_ENV"
    private const val DD_SERVICE_ENV_VAR = "DD_SERVICE"
    private const val DD_VERSION_ENV_VAR = "DD_VERSION"
    private const val JAVA_OPTS_ENV_VAR = "JAVA_OPTS"
    private const val PUBLISH_METRICS_ENV_VAR = "PUBLISH_METRICS"
    private const val CONTROL_PLANE_AUTH_ENDPOINT_ENV_VAR = "CONTROL_PLANE_AUTH_ENDPOINT"
    private const val DATA_PLANE_SERVICE_ACCOUNT_CREDENTIALS_PATH_ENV_VAR = "DATA_PLANE_SERVICE_ACCOUNT_CREDENTIALS_PATH"
    private const val DATA_PLANE_SERVICE_ACCOUNT_EMAIL_ENV_VAR = "DATA_PLANE_SERVICE_ACCOUNT_EMAIL"
    private const val AIRBYTE_API_AUTH_HEADER_NAME_ENV_VAR = "AIRBYTE_API_AUTH_HEADER_NAME"
    private const val AIRBYTE_API_AUTH_HEADER_VALUE_ENV_VAR = "AIRBYTE_API_AUTH_HEADER_VALUE"
    private const val INTERNAL_API_HOST_ENV_VAR = "INTERNAL_API_HOST"
    private const val ACCEPTANCE_TEST_ENABLED_VAR = "ACCEPTANCE_TEST_ENABLED"
    private const val DD_INTEGRATION_ENV_VAR_FORMAT = "DD_INTEGRATION_%s_ENABLED"
    private const val WORKER_V2_MICRONAUT_ENV = "worker-v2"
    private const val WORKLOAD_API_HOST_ENV_VAR = "WORKLOAD_API_HOST"
    private const val WORKLOAD_API_CONNECT_TIMEOUT_SECONDS_ENV_VAR = "WORKLOAD_API_CONNECT_TIMEOUT_SECONDS"
    private const val WORKLOAD_API_READ_TIMEOUT_SECONDS_ENV_VAR = "WORKLOAD_API_READ_TIMEOUT_SECONDS"
    private const val WORKLOAD_API_RETRY_DELAY_SECONDS_ENV_VAR = "WORKLOAD_API_RETRY_DELAY_SECONDS"
    private const val WORKLOAD_API_MAX_RETRIES_ENV_VAR = "WORKLOAD_API_MAX_RETRIES"

    // secrets
    const val AWS_ASSUME_ROLE_ACCESS_KEY_ID_ENV_VAR = "AWS_ASSUME_ROLE_ACCESS_KEY_ID"
    const val AWS_ASSUME_ROLE_SECRET_ACCESS_KEY_ENV_VAR = "AWS_ASSUME_ROLE_SECRET_ACCESS_KEY"
    const val WORKLOAD_API_BEARER_TOKEN_ENV_VAR = "WORKLOAD_API_BEARER_TOKEN"
  }
}
