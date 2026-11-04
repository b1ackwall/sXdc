package com.v1per.sxdc.download

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** String-resource lookup behind an interface, so the queue engine can run on the plain JVM. */
internal interface StringProvider {
    fun get(
        @StringRes id: Int,
        vararg args: Any,
    ): String

    /** A plurals resource for [quantity]; [args] are the format arguments (usually the count first). */
    fun plural(
        @PluralsRes id: Int,
        quantity: Int,
        vararg args: Any,
    ): String
}

internal class ContextStringProvider(private val context: Context) : StringProvider {
    // The no-args branch calls getString(id) rather than getString(id, *emptyArray()): the
    // varargs overload runs the text through String.format, which the old call sites never did.
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, quantity, *args)
}
