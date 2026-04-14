import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import hudson.tasks.test.AbstractTestResultAction

class TelegramProxyNotify implements Serializable {
  Script script

  TelegramProxyNotify() {}

  TelegramProxyNotify(Script script) {
    this.script = script
  }

  def send(String repositoryName, String buildStatus, def messageId = null, def duration = null, Map options = [:]) {
    buildStatus = resolveBuildStatus(buildStatus, options)
    def parseMode = options.parseMode ?: 'HTML'
    def disableNotification = resolveDisableNotification(options)
    def proxyUrlCredentialId = (options.proxyUrlCredentialsId ?: 'telegram_proxy_url').toString()
    def authSecretCredentialId = (options.authSecretCredentialsId ?: 'telegram_proxy_auth_secret').toString()
    def credsCredentialId = (options.credsCredentialsId ?: 'telegram_proxy_creds').toString()
    def parseModeCredentialId = options.parseModeCredentialsId ? options.parseModeCredentialsId.toString() : null
    def disableNotificationCredentialId = options.disableNotificationCredentialsId ? options.disableNotificationCredentialsId.toString() : null

    def image = getStatusImage(buildStatus)
    def blueOceanUrl = "https://ci.101internet.ru/blue/organizations/jenkins/${repositoryName}/detail/${script.env.BRANCH_NAME}/${script.env.BUILD_NUMBER}/pipeline/"
    def title = "${buildStatus} ${image}"
    def text = "<b>Link:</b> <a href=\"${script.env.RUN_DISPLAY_URL}\">${script.env.JOB_NAME}</a> [<a href=\"${blueOceanUrl}\">BlueOcean</a>]"
    text += "\r\n<b>Build:</b> ${script.env.BUILD_NUMBER}"

    if (buildStatus != 'CLONE') {
      def author = script.sh(returnStdout: true, script: "git log -1 --pretty=format:'%an'").trim()
      text += "\r\n<b>Author:</b> ${author}"
    }

    if (duration) {
      text += "\r\n<b>Duration:</b> ${duration.replace(' and counting', '')}"
    }

    if (buildStatus != 'CLONE') {
      def commitMessage = script.sh(returnStdout: true, script: 'git log -1 --format=%B').trim()
      text += "\r\n<b>Message:</b> ${commitMessage}"
    }

    text += "\r\n<b>Tests:</b> ${getTestSummary(buildStatus)}"

    def proxyUrl = options.proxyUrl?.toString()?.trim()
    def authSecret = options.authSecret?.toString()?.trim()
    def creds = options.creds?.toString()?.trim()

    def bindings = []
    if (!proxyUrl) {
      bindings << [$class: 'StringBinding', credentialsId: proxyUrlCredentialId, variable: 'TELEGRAM_PROXY_URL_CRED']
    }
    if (!authSecret) {
      bindings << [$class: 'StringBinding', credentialsId: authSecretCredentialId, variable: 'TELEGRAM_PROXY_AUTH_SECRET_CRED']
    }
    if (!creds) {
      bindings << [$class: 'StringBinding', credentialsId: credsCredentialId, variable: 'TELEGRAM_PROXY_CREDS_CRED']
    }
    if (!options.parseMode && parseModeCredentialId) {
      bindings << [$class: 'StringBinding', credentialsId: parseModeCredentialId, variable: 'TELEGRAM_PROXY_PARSE_MODE_CRED']
    }
    if (!options.containsKey('disableNotification') && disableNotificationCredentialId) {
      bindings << [$class: 'StringBinding', credentialsId: disableNotificationCredentialId, variable: 'TELEGRAM_PROXY_DISABLE_NOTIFICATION_CRED']
    }

    def runRequest = {
      proxyUrl = proxyUrl ?: TELEGRAM_PROXY_URL_CRED?.toString()?.trim()
      authSecret = authSecret ?: TELEGRAM_PROXY_AUTH_SECRET_CRED?.toString()?.trim()
      creds = creds ?: TELEGRAM_PROXY_CREDS_CRED?.toString()?.trim()

      if (!options.parseMode && parseModeCredentialId) {
        parseMode = TELEGRAM_PROXY_PARSE_MODE_CRED?.toString()?.trim() ?: parseMode
      }
      if (!options.containsKey('disableNotification') && disableNotificationCredentialId) {
        disableNotification = toBoolean(TELEGRAM_PROXY_DISABLE_NOTIFICATION_CRED)
      }

      if (!proxyUrl) {
        throw new IllegalArgumentException("Missing proxy URL. Configure '${proxyUrlCredentialId}' credential or pass options.proxyUrl.")
      }
      if (!authSecret) {
        throw new IllegalArgumentException("Missing proxy auth secret. Configure '${authSecretCredentialId}' credential or pass options.authSecret.")
      }
      if (!creds) {
        throw new IllegalArgumentException("Missing proxy creds. Configure '${credsCredentialId}' credential or pass options.creds.")
      }

      def bodyData = [
        title     : title.trim(),
        text      : text,
        creds     : creds,
        parse_mode: parseMode
      ]

      if (messageId) {
        bodyData.message_id = messageId
      } else {
        bodyData.disable_notification = disableNotification
      }

      script.echo("Telegram proxy: sending notification. status=${buildStatus}, job=${script.env.JOB_NAME}, build=${script.env.BUILD_NUMBER}")
      script.echo("Telegram proxy: URL configured = ${proxyUrl ? 'yes' : 'no'}, auth configured = ${authSecret ? 'yes' : 'no'}, creds configured = ${creds ? 'yes' : 'no'}")

      def post = new URL(proxyUrl).openConnection()
      post.setConnectTimeout(10000)
      post.setReadTimeout(15000)
      post.setRequestMethod('POST')
      post.setDoOutput(true)
      post.setRequestProperty('Content-Type', 'application/json')
      post.setRequestProperty('X-Authentication', authSecret)

      def httpRequestBodyWriter = new BufferedWriter(new OutputStreamWriter(post.getOutputStream()))
      httpRequestBodyWriter.write(JsonOutput.toJson(bodyData))
      httpRequestBodyWriter.close()

      def responseCode = post.getResponseCode()
      def responseBody = readResponseBody(post, responseCode)

      script.echo("Telegram proxy: response code = ${responseCode}")
      if (responseBody) {
        script.echo("Telegram proxy: response body = ${responseBody}")
      }

      if (responseCode == 400) {
        def badRequestDescription = extractBadRequestDescription(responseBody)
        if (badRequestDescription) {
          script.echo("Telegram proxy returned HTTP 400, skipping failure. Reason: ${badRequestDescription}")
        } else {
          script.echo("Telegram proxy returned HTTP 400, skipping failure. Response: ${responseBody}")
        }
      } else if (responseCode < 200 || responseCode >= 300) {
        throw new RuntimeException("Proxy request failed with HTTP ${responseCode}: ${responseBody}")
      }

      if (!messageId && responseBody && responseCode >= 200 && responseCode < 300) {
        def parsed = new JsonSlurper().parseText(responseBody)
        messageId = parsed?.result?.message_id ?: parsed?.message_id
        script.echo("Telegram proxy: message_id = ${messageId}")
      }
    }

    if (bindings) {
      script.withCredentials(bindings) {
        runRequest()
      }
    } else {
      runRequest()
    }

    return messageId
  }

  private String getTestSummary(String buildStatus) {
    def testResultAction = script.currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    def summary = ''

    if (testResultAction != null) {
      def total = testResultAction.getTotalCount()
      def failed = testResultAction.getFailCount()
      def skipped = testResultAction.getSkipCount()

      summary += "Passed: ${total - failed - skipped}"
      summary += ", Failed: ${failed} ${testResultAction.failureDiffString}"
      summary += ", Skipped: ${skipped}"
    } else if (buildStatus == 'STARTED') {
      summary = 'Tests are preparing'
    } else {
      summary = 'No tests found'
    }

    return summary
  }

  private String getStatusImage(String buildStatus) {
    switch (buildStatus) {
      case 'BUILD_FAILURE':
        return '📦⛔'
      case 'DEPLOY_FAILURE':
        return '🚀⛔'
      case 'CLONE':
        return '📥'
      case 'SKIP':
        return '⏭️'
      case 'BUILD':
        return '📦'
      case 'TESTS':
        return '🧪'
      case 'PUBLISH':
        return '📤'
      case 'DEPLOY':
        return '🚀'
      case 'BUILD_STORYBOOK':
        return '📦'
      case 'PUBLISH_STORYBOOK':
        return '📤'
      case 'DEPLOY_STORYBOOK':
        return '🚀'
      case 'ABORTED':
        return '⚠️'
      case 'FAILURE':
        return '⛔'
      case 'SUCCESS':
        return '✅'
      default:
        return 'ℹ️'
    }
  }

  private String resolveBuildStatus(def buildStatus, Map options) {
    def normalizedStatus = (buildStatus ?: 'SUCCESS').toString().trim().toUpperCase()
    if (normalizedStatus == 'FAILED') {
      normalizedStatus = 'FAILURE'
    }

    if (normalizedStatus == 'FAILURE') {
      def failedStage = options.failedStage?.toString()?.trim()?.toUpperCase()
      if (failedStage == 'BUILD') {
        return 'BUILD_FAILURE'
      }
      if (failedStage == 'DEPLOY') {
        return 'DEPLOY_FAILURE'
      }
    }

    return normalizedStatus
  }

  private Boolean resolveDisableNotification(Map options) {
    if (options.containsKey('disableNotification')) {
      return toBoolean(options.disableNotification)
    }
    return false
  }

  private Boolean toBoolean(def value) {
    if (value == null) {
      return false
    }
    if (value instanceof Boolean) {
      return value
    }
    return value.toString().trim().equalsIgnoreCase('true')
  }

  private String extractBadRequestDescription(String responseBody) {
    if (!responseBody) {
      return ''
    }

    try {
      def parsed = new JsonSlurper().parseText(responseBody)
      return parsed?.description?.toString() ?: parsed?.error?.toString() ?: parsed?.details?.description?.toString() ?: ''
    } catch (Exception ignored) {
      return ''
    }
  }

  private String readResponseBody(def connection, int responseCode) {
    try {
      if (responseCode >= 200 && responseCode < 400 && connection.getInputStream() != null) {
        return connection.getInputStream().getText()
      }
    } catch (Exception ignored) {
    }

    try {
      if (connection.getErrorStream() != null) {
        return connection.getErrorStream().getText()
      }
    } catch (Exception ignored) {
    }

    return ''
  }
}
