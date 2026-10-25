package io.github.duwenji.yorisoi.cycleguide.channel

import android.content.Intent
import android.net.Uri
import io.github.duwenji.yorisoi.kit.channel.report.Report
import io.github.duwenji.yorisoi.kit.channel.report.ReportTarget

/** CycleGuide's reports on a channel (kit-channel [Report]): an email the user writes and sends themselves. */
object AppReport {
    /** The app's contact, as in the privacy policy. */
    const val CONTACT = "duwenji@gmail.com"

    val report = Report("CycleGuide", CONTACT)

    /** Opens the user's mail app with the draft; nothing is sent until they send it. */
    fun mail(target: ReportTarget, appVersion: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(target.contact))
            putExtra(Intent.EXTRA_SUBJECT, report.subject(target, null))
            putExtra(Intent.EXTRA_TEXT, report.body(target, null, appVersion))
        }
}
