import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.BufferedWriter
import java.io.OutputStreamWriter

class TelegramProxyNotify implements Serializable {
  Script script

  TelegramProxyNotify(Script script) {
    this.script = script
  }

  def send(String repositoryName, String buildStatus, def messageId = null, def duration = null, Map options = [:]) {
    def parseMode = options.parseMode ?: 'HTML'

    def proxyUrl
    def authSecret
    def creds

    script.withCredentials([
      [$class: 'StringBinding', credentialsId: 'telegram_proxy_url', variable: 'TG_URL'],
      [$class: 'StringBinding', credentialsId: 'telegram_proxy_auth_secret', variable: 'TG_AUTH'],
      [$class: 'StringBinding', credentialsId: 'telegram_proxy_creds', variable: 'TG_CREDS']
    ]) {
      proxyUrl = script.env.TG_URL
      authSecret = script.env.TG_AUTH
      creds = script.env.TG_CREDS
    }

    if (!proxyUrl || !authSecret || !creds) {
      throw new RuntimeException("Missing Telegram proxy credentials")
    }

    def title = "${buildStatus}"
    def text = "<b>${script.env.JOB_NAME}</b>\nBuild #${script.env.BUILD_NUMBER}"

    if (duration) {
      text += "\nDuration: ${duration.replace(' and counting', '')}"
    }

    def bodyData = [
      title     : title,
      text      : text,
      creds     : creds,
      parse_mode: parseMode,
      disable_notification: (buildStatus == 'SUCCESS')
    ]

    def post = new URL(proxyUrl).openConnection()
    post.setRequestMethod('POST')
    post.setDoOutput(true)
    post.setRequestProperty('Content-Type', 'application/json')
    post.setRequestProperty('X-Authentication', authSecret)

    def writer = new BufferedWriter(new OutputStreamWriter(post.getOutputStream()))
    writer.write(JsonOutput.toJson(bodyData))
    writer.close()

    def responseCode = post.getResponseCode()

    if (responseCode < 200 || responseCode >= 300) {
      def response = post.getErrorStream()?.getText()
      throw new RuntimeException("Proxy error ${responseCode}: ${response}")
    }
  }
}