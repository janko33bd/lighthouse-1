package lighthouse

import lighthouse.utils.I18nUtil.tr

/**
 * Hard-coded list of project servers so the app can randomly pick between them and load balance the work.
 */
public object ServerList {
    enum class SubmitType { EMAIL, WEB }
    class Entry(val hostName: String, val submitAddress: String, val instructions: String, val submitType: SubmitType)

    val servers = listOf(
            Entry("localhost:13765", "http://localhost:13765/projects/new", tr("Submission via the web. Project must be legal under EU/US law."), SubmitType.WEB)
    )
    @JvmField val hostnameToServer: Map<String, Entry> = servers.map { Pair(it.hostName, it) }.toMap()

    fun pickRandom(): Entry = servers[(Math.random() * servers.size).toInt()]
}
