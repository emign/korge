@file:Suppress("NOTHING_TO_INLINE")

package com.soywiz.korge.tween

import com.soywiz.kds.iterators.*
import com.soywiz.klock.*
import com.soywiz.kmem.*
import com.soywiz.korge.component.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*
import com.soywiz.korio.async.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.reflect.*

class TweenComponent(
	override val view: View,
	private val vs: List<V2<*>>,
	val time: Long? = null,
	val easing: Easing = Easing.LINEAR,
	val callback: (Double) -> Unit,
	val c: CancellableContinuation<Unit>
) : UpdateComponent {
	var elapsedMs = 0
	val ctime : Long = time ?: vs.map { it.endTime }.max()?.toLong() ?: 1000L
	var cancelled = false
	var done = false

	init {
		c.invokeOnCancellation {
			cancelled = true
			//println("TWEEN CANCELLED[$this, $vs]: $elapsed")
		}
		update(0.0)
	}

	fun completeOnce() {
		if (!done) {
			done = true
			detach()
			c.resume(Unit)
			//println("TWEEN COMPLETED[$this, $vs]: $elapsed")
		}
	}

	override fun update(ms: Double) {
        if (cancelled) {
            //println(" --> cancelled")
            return completeOnce()
        }
		val dtMs = ms.toInt()
		//println("TWEEN UPDATE[$this, $vs]: $elapsed + $dtMs")
		elapsedMs += dtMs

		val ratio = (elapsedMs.toDouble() / ctime.toDouble()).clamp(0.0, 1.0)
		setToMs(elapsedMs)
		callback(easing(ratio))

		if (ratio >= 1.0) {
			//println(" --> completed")
			return completeOnce()
		}
	}

	fun setToMs(elapsed: Int) {
        if (elapsed == 0) {
            vs.fastForEach { v ->
                v.init()
            }
        }
		vs.fastForEach { v ->
			val durationInTween = (v.duration ?: (ctime - v.startTime))
			val elapsedInTween = (elapsed - v.startTime).clamp(0L, durationInTween)
			val ratioInTween = if (durationInTween <= 0.0) 1.0 else elapsedInTween.toDouble() / durationInTween.toDouble()
			v.set(easing(ratioInTween))
		}
	}

	override fun toString(): String = "TweenComponent($view)"
}

private val emptyCallback: (Double) -> Unit = {}

suspend fun View?.tween(
	vararg vs: V2<*>,
	time: TimeSpan,
	easing: Easing = Easing.LINEAR,
	callback: (Double) -> Unit = emptyCallback
): Unit {
	if (this != null) {
		var tc: TweenComponent? = null
		try {
			withTimeout(300 + time.millisecondsLong * 2) {
				suspendCancellableCoroutine<Unit> { c ->
					val view = this@tween
					//println("STARTED TWEEN at thread $currentThreadId")
					tc = TweenComponent(view, vs.toList(), time.millisecondsLong, easing, callback, c).also { it.attach() }
				}
			}
		} catch (e: TimeoutCancellationException) {
			tc?.setToMs(time.millisecondsInt)
		}
	}
}

suspend fun View?.tweenAsync(
	vararg vs: V2<*>,
	time: TimeSpan,
	easing: Easing = Easing.LINEAR,
	callback: (Double) -> Unit = emptyCallback
) = asyncImmediately(coroutineContext) { tween(*vs, time = time, easing = easing, callback = callback) }

fun View?.tweenAsync(
	vararg vs: V2<*>,
	coroutineContext: CoroutineContext,
	time: TimeSpan,
	easing: Easing = Easing.LINEAR,
	callback: (Double) -> Unit = emptyCallback
) = asyncImmediately(coroutineContext) { tween(*vs, time = time, easing = easing, callback = callback) }

suspend fun View.show(time: TimeSpan, easing: Easing = Easing.LINEAR) =
	tween(this::alpha[1.0], time = time, easing = easing) { this.visible = true }

suspend fun View.hide(time: TimeSpan, easing: Easing = Easing.LINEAR) =
	tween(this::alpha[0.0], time = time, easing = easing)

suspend inline fun View.moveTo(x: Double, y: Double, time: TimeSpan, easing: Easing = Easing.LINEAR) = tween(this::x[x], this::y[y], time = time, easing = easing)
suspend inline fun View.moveBy(dx: Double, dy: Double, time: TimeSpan, easing: Easing = Easing.LINEAR) = tween(this::x[this.x + dx], this::y[this.y + dy], time = time, easing = easing)
suspend inline fun View.scaleTo(sx: Double, sy: Double, time: TimeSpan, easing: Easing = Easing.LINEAR) = tween(this::scaleX[sx], this::scaleY[sy], time = time, easing = easing)

@Deprecated("Kotlin/Native boxes inline+Number")
suspend inline fun View.moveTo(x: Number, y: Number, time: TimeSpan, easing: Easing = Easing.LINEAR) = moveTo(x.toDouble(), y.toDouble(), time, easing)
@Deprecated("Kotlin/Native boxes inline+Number")
suspend inline fun View.moveBy(dx: Number, dy: Number, time: TimeSpan, easing: Easing = Easing.LINEAR) = moveBy(dx.toDouble(), dy.toDouble(), time, easing)
@Deprecated("Kotlin/Native boxes inline+Number")
suspend inline fun View.scaleTo(sx: Number, sy: Number, time: TimeSpan, easing: Easing = Easing.LINEAR) = scaleTo(sx.toDouble(), sy.toDouble(), time, easing)

suspend inline fun View.rotateTo(deg: Angle, time: TimeSpan, easing: Easing = Easing.LINEAR) =
	tween(this::rotationRadians[deg.radians], time = time, easing = easing)

suspend inline fun View.rotateBy(ddeg: Angle, time: TimeSpan, easing: Easing = Easing.LINEAR) =
	tween(this::rotationRadians[this.rotationRadians + ddeg.radians], time = time, easing = easing)

@Suppress("UNCHECKED_CAST")
data class V2<V>(
	val key: KMutableProperty0<V>,
	var initial: V,
	val end: V,
	val interpolator: (Double, V, V) -> V,
    val includeStart: Boolean,
	val startTime: Long = 0,
	val duration: Long? = null
) {
	val endTime = startTime + (duration ?: 0)

	@Deprecated("", replaceWith = ReplaceWith("key .. (initial...end)", "com.soywiz.korge.tween.rangeTo"))
	constructor(key: KMutableProperty0<V>, initial: V, end: V, includeStart: Boolean = false) : this(key, initial, end, ::_interpolateAny, includeStart)

    fun init() {
        if (!includeStart) {
            initial = key.get()
        }
    }
	fun set(ratio: Double) = key.set(interpolator(ratio, initial, end))

	override fun toString(): String =
		"V2(key=${key.name}, range=[$initial-$end], startTime=$startTime, duration=$duration)"
}

operator fun <V> KMutableProperty0<V>.get(end: V) = V2(this, this.get(), end, ::_interpolateAny, includeStart = false)
operator fun <V> KMutableProperty0<V>.get(initial: V, end: V) = V2(this, initial, end, ::_interpolateAny, includeStart = true)

@PublishedApi
internal fun _interpolate(ratio: Double, l: Double, r: Double) = ratio.interpolate(l, r)

@PublishedApi
internal fun _interpolateFloat(ratio: Double, l: Float, r: Float) = ratio.interpolate(l, r)

@PublishedApi
internal fun <V> _interpolateAny(ratio: Double, l: V, r: V) = ratio.interpolateAny(l, r)

@PublishedApi
internal fun _interpolateColor(ratio: Double, l: RGBA, r: RGBA): RGBA = RGBA.mixRgba(l, r, ratio)

@PublishedApi
internal fun _interpolateAngle(ratio: Double, l: Angle, r: Angle): Angle = Angle(_interpolate(ratio, l.radians, r.radians))

@PublishedApi
internal fun _interpolateTimeSpan(ratio: Double, l: TimeSpan, r: TimeSpan): TimeSpan = _interpolate(ratio, l.milliseconds, r.milliseconds).milliseconds

//inline operator fun KMutableProperty0<Float>.get(end: Number) = V2(this, this.get(), end.toFloat(), ::_interpolateFloat)
//inline operator fun KMutableProperty0<Float>.get(initial: Number, end: Number) =
//	V2(this, initial.toFloat(), end.toFloat(), ::_interpolateFloat)

inline operator fun KMutableProperty0<Double>.get(end: Double) = V2(this, this.get(), end, ::_interpolate, includeStart = false)
inline operator fun KMutableProperty0<Double>.get(initial: Double, end: Double) = V2(this, initial, end, ::_interpolate, true)

inline operator fun KMutableProperty0<Double>.get(end: Int) = get(end.toDouble())
inline operator fun KMutableProperty0<Double>.get(initial: Int, end: Int) = get(initial.toDouble(), end.toDouble())

inline operator fun KMutableProperty0<Double>.get(end: Float) = get(end.toDouble())
inline operator fun KMutableProperty0<Double>.get(initial: Float, end: Float) = get(initial.toDouble(), end.toDouble())

@Deprecated("Kotlin/Native boxes inline+Number")
inline operator fun KMutableProperty0<Double>.get(end: Number) = get(end.toDouble())
@Deprecated("Kotlin/Native boxes inline+Number")
inline operator fun KMutableProperty0<Double>.get(initial: Number, end: Number) = get(initial.toDouble(), end.toDouble())

inline operator fun KMutableProperty0<RGBA>.get(end: RGBA) = V2(this, this.get(), end, ::_interpolateColor, includeStart = false)
inline operator fun KMutableProperty0<RGBA>.get(initial: RGBA, end: RGBA) =
	V2(this, initial, end, ::_interpolateColor, includeStart = true)

inline operator fun KMutableProperty0<Angle>.get(end: Angle) = V2(this, this.get(), end, ::_interpolateAngle, includeStart = false)
inline operator fun KMutableProperty0<Angle>.get(initial: Angle, end: Angle) =
	V2(this, initial, end, ::_interpolateAngle, includeStart = true)

inline operator fun KMutableProperty0<TimeSpan>.get(end: TimeSpan) = V2(this, this.get(), end, ::_interpolateTimeSpan, includeStart = false)
inline operator fun KMutableProperty0<TimeSpan>.get(initial: TimeSpan, end: TimeSpan) =
    V2(this, initial, end, ::_interpolateTimeSpan, includeStart = true)

fun <V> V2<V>.easing(easing: Easing): V2<V> =
	this.copy(interpolator = { ratio, a, b -> this.interpolator(easing(ratio), a, b) })

inline fun <V> V2<V>.delay(startTime: TimeSpan) = this.copy(startTime = startTime.millisecondsLong)
inline fun <V> V2<V>.duration(duration: TimeSpan) = this.copy(duration = duration.millisecondsLong)

inline fun <V> V2<V>.linear() = this
inline fun <V> V2<V>.smooth() = this.easing(Easing.SMOOTH)
inline fun <V> V2<V>.easeIn() = this.easing(Easing.EASE_IN)
inline fun <V> V2<V>.easeOut() = this.easing(Easing.EASE_OUT)
inline fun <V> V2<V>.easeInOut() = this.easing(Easing.EASE_IN_OUT)
inline fun <V> V2<V>.easeOutIn() = this.easing(Easing.EASE_OUT_IN)
inline fun <V> V2<V>.easeInBack() = this.easing(Easing.EASE_IN_BACK)
inline fun <V> V2<V>.easeOutBack() = this.easing(Easing.EASE_OUT_BACK)
inline fun <V> V2<V>.easeInOutBack() = this.easing(Easing.EASE_IN_OUT_BACK)
inline fun <V> V2<V>.easeOutInBack() = this.easing(Easing.EASE_OUT_IN_BACK)

inline fun <V> V2<V>.easeInElastic() = this.easing(Easing.EASE_IN_ELASTIC)
inline fun <V> V2<V>.easeOutElastic() = this.easing(Easing.EASE_OUT_ELASTIC)
inline fun <V> V2<V>.easeInOutElastic() = this.easing(Easing.EASE_IN_OUT_ELASTIC)
inline fun <V> V2<V>.easeOutInElastic() = this.easing(Easing.EASE_OUT_IN_ELASTIC)

inline fun <V> V2<V>.easeInBounce() = this.easing(Easing.EASE_IN_BOUNCE)
inline fun <V> V2<V>.easeOutBounce() = this.easing(Easing.EASE_OUT_BOUNCE)
inline fun <V> V2<V>.easeInOutBounce() = this.easing(Easing.EASE_IN_OUT_BOUNCE)
inline fun <V> V2<V>.easeOutInBounce() = this.easing(Easing.EASE_OUT_IN_BOUNCE)

inline fun <V> V2<V>.easeInQuad() = this.easing(Easing.EASE_IN_QUAD)
inline fun <V> V2<V>.easeOutQuad() = this.easing(Easing.EASE_OUT_QUAD)
inline fun <V> V2<V>.easeInOutQuad() = this.easing(Easing.EASE_IN_OUT_QUAD)

inline fun <V> V2<V>.easeSine() = this.easing(Easing.EASE_SINE)
