/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.core

import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.max

/** French formatting helpers (views, durations, dates, errors). */
object Fmt {
    private val fr: Locale = Locale.FRANCE

    fun views(n: Long): String = when {
        n < 0 -> ""
        n == 0L -> "Aucune vue"
        n == 1L -> "1 vue"
        n >= 1_000_000 -> "${compact(n)} de vues"
        else -> "${compact(n)} vues"
    }

    fun viewers(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 spectateur"
        else -> "${compact(n)} spectateurs"
    }

    fun subscribers(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 abonné"
        n >= 1_000_000 -> "${compact(n)} d'abonnés"
        else -> "${compact(n)} abonnés"
    }

    fun compact(n: Long): String = when {
        n >= 1_000_000_000L -> oneDecimal(n / 1e9) + " Md"
        n >= 1_000_000L -> oneDecimal(n / 1e6) + " M"
        n >= 10_000L -> "${n / 1000} k"
        n >= 1_000L -> oneDecimal(n / 1e3) + " k"
        else -> n.toString()
    }

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10).toLong() / 10.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(fr, "%.1f", rounded)
        }
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(fr, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(fr, "%d:%02d", m, s)
        }
    }

    fun time(ms: Long): String = duration(max(0L, ms / 1000)).ifEmpty { "0:00" }

    fun relative(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0) return ""
        val diff = (System.currentTimeMillis() - epochMs) / 1000
        if (diff < 60) return "à l'instant"
        val minutes = diff / 60
        if (minutes < 60) return "il y a $minutes min"
        val hours = minutes / 60
        if (hours < 24) return "il y a $hours h"
        val days = hours / 24
        if (days < 7) return if (days == 1L) "hier" else "il y a $days jours"
        if (days < 30) {
            val weeks = days / 7
            return if (weeks == 1L) "il y a 1 semaine" else "il y a $weeks semaines"
        }
        if (days < 365) return "il y a ${days / 30} mois"
        val years = days / 365
        return if (years == 1L) "il y a 1 an" else "il y a $years ans"
    }

    /** "Music" → "Musique": YouTube categories come in English from the metadata. */
    fun category(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "music" -> "Musique"
            "gaming" -> "Jeux vidéo"
            "entertainment" -> "Divertissement"
            "sports" -> "Sport"
            "news & politics" -> "Actualités"
            "education" -> "Éducation"
            "science & technology" -> "Science et techno"
            "film & animation" -> "Films et animation"
            "travel & events" -> "Voyages"
            "people & blogs" -> "Vlogs"
            "comedy" -> "Humour"
            "howto & style" -> "Tutos et style"
            "autos & vehicles" -> "Auto"
            "pets & animals" -> "Animaux"
            "nonprofits & activism" -> "Engagement"
            else -> raw
        }
    }

    fun error(t: Throwable): String {
        var cause: Throwable = t
        // Unwrap the generic wrappers to find something meaningful.
        while (cause.cause != null && cause.cause !== cause &&
            (cause is RuntimeException || cause.javaClass.name.startsWith("java.util.concurrent"))
        ) {
            cause = cause.cause!!
        }
        return when (cause) {
            is SignInConfirmNotBotException ->
                "YouTube demande une vérification anti-robot pour ta connexion. Réessaie dans quelques minutes."
            is AgeRestrictedContentException ->
                "Cette vidéo est soumise à une limite d'âge et nécessite un compte YouTube."
            is GeographicRestrictionException -> "Cette vidéo n'est pas disponible dans ton pays."
            is PrivateContentException -> "Cette vidéo est privée."
            is PaidContentException -> "Cette vidéo est payante ou réservée aux membres de la chaîne."
            is ContentNotAvailableException -> "Ce contenu n'est pas disponible."
            is ReCaptchaException -> "YouTube limite temporairement les requêtes. Réessaie dans un moment."
            is UnknownHostException, is ConnectException ->
                "Pas de connexion Internet. Vérifie le réseau de ta TV."
            is SocketTimeoutException -> "La connexion est trop lente. Réessaie."
            is java.io.IOException -> cause.message ?: "Problème de connexion avec YouTube."
            else -> "Une erreur est survenue (${cause.javaClass.simpleName})."
        }
    }
}
