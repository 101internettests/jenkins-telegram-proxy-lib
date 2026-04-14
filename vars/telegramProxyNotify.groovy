def call(String buildStatus, Map options = [:]) {
    def notifier = new TelegramProxyNotify(this)
    return notifier.send(
        env.JOB_NAME,
        buildStatus,
        options.messageId ?: null,
        currentBuild.durationString,
        options
    )
}