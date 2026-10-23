package com.oddessentials.agentguild

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.Harness.Companion.waitFor
import com.oddessentials.agentguild.model.Json
import com.oddessentials.agentguild.model.SignInLink
import com.oddessentials.agentguild.scan.QrReader
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A sign-in QR code, drawn by the scratch manager's own code, read through ML Kit's bundled model. */
@RunWith(AndroidJUnit4::class)
class QrTest {
    @Test
    fun readsTheSignInCodeTheManagerDraws() {
        val link = "https://mini.tail1234.ts.net/#token=${"0123456789abcdef".repeat(3)}"
        lateinit var web: WebView
        // A WebView runs script only once it is in a window.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            web = WebView(activity)
            activity.addContentView(web, ViewGroup.LayoutParams(600, 600))
            web.settings.javaScriptEnabled = true
            // The manager's Remote access dialog paints its QR code with these two modules.
            web.loadDataWithBaseURL(
                "${Harness.managerAddress}/",
                """<!doctype html><canvas id="sign-in"></canvas>
                <script>
                Promise.all([import('/vendor/qrcode.mjs'), import('/remote-access.js')])
                  .then(([{ default: qrcode }, { paintQr }]) => {
                    const canvas = document.getElementById('sign-in');
                    paintQr(canvas, qrcode, ${Json.quote(link)});
                    window.signInCode = canvas.toDataURL('image/png');
                  })
                  .catch((error) => { window.signInCode = 'error: ' + error; });
                </script>""",
                "text/html",
                "utf-8",
                null,
            )
        }
        try {
            waitFor(web, "typeof window.signInCode === 'string'")
            val dataUrl = evaluateString(web, "window.signInCode")!!
            assertTrue(dataUrl.take(200), dataUrl.startsWith("data:image/png;base64,"))
            val png = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size).let { Bitmap.createScaledBitmap(it, it.width * 2, it.height * 2, false) }

            val scanner = QrReader.client()
            val texts = Tasks.await(QrReader.read(scanner, bitmap), 30, TimeUnit.SECONDS)
            scanner.close()

            assertEquals(listOf(link), texts)
            assertEquals(SignInLink("https://mini.tail1234.ts.net", "0123456789abcdef".repeat(3)), AgentGuildApp.parseLink(texts.single()))
        } finally {
            scenario.onActivity { web.destroy() }
            scenario.close()
        }
    }
}
