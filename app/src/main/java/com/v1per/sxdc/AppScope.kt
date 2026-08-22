package com.v1per.sxdc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime IO scope for the small amount of queue bookkeeping that must not be tied to a
 * component's lifetime.
 *
 * Two callers, both of which would be wrong on a narrower scope:
 *  - SxdcApp's startup recovery pass, which has no component to scope to at all.
 *  - DownloadService.onTimeout, which has seconds to record a failure *while* the service is being
 *    torn down -- a write on the service's own scope would be cancelled before it landed.
 *
 * SupervisorJob so one failed write cannot take the scope down with it. Never cancelled: it holds
 * nothing between writes, and the only thing that could cancel it is the process ending.
 */
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
