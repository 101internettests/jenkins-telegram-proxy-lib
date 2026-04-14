def call(String buildStatus, Map options = [:]) {
    def notifier = new TelegramProxyNotify()
    notifier.script = this
    return notifier.send(
        env.JOB_NAME,
        buildStatus,
        options.messageId ?: null,
        currentBuild.durationString,
        options
    )
}
