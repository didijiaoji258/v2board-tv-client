package com.v2rayng.mytv.ui.home

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.animation.doOnEnd
import com.v2rayng.mytv.R
import com.v2rayng.mytv.vpn.VpnState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 自定义连接按钮视图，复刻手机端动画效果：
 * - 断开时：静态渐变圆
 * - 连接中：旋转弧 + 呼吸光晕 + 按钮缩放
 * - 已连接：轨道旋转点 + 扩散波纹环 + 呼吸光晕 + 全彩渐变弧
 */
class ConnectButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---- Colors ----
    private val colorGradientStart = Color.parseColor("#6C3CE1")   // 紫
    private val colorGradientEnd   = Color.parseColor("#00C6FF")   // 蓝
    private val colorAccent        = Color.parseColor("#7B61FF")
    private val colorSuccess       = Color.parseColor("#00E676")
    private val colorTrack         = Color.parseColor("#1E2347")

    // ---- State ----
    var vpnState: VpnState = VpnState.DISCONNECTED
        set(value) {
            val old = field; field = value
            if (old != value) onStateChanged(old, value)
        }

    // ---- Animation values (updated by animators) ----
    private var orbitAngle1    = 0f   // degrees, fast orbit
    private var orbitAngle2    = 0f   // degrees, slow reverse orbit
    private var breathAlpha    = 0.15f
    private var connectSpin    = 0f   // degrees, connecting arc rotation
    private var buttonScale    = 1f
    private var ringAlpha1     = 0f; private var ringScale1 = 0.6f
    private var ringAlpha2     = 0f; private var ringScale2 = 0.6f
    private var ringAlpha3     = 0f; private var ringScale3 = 0.6f

    // ---- VPN key icon drawable ----
    private val vpnKeyDrawable by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_vpn_key)
    }

    // ---- Painters ----
    private val paintArc  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintDot  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ---- Animators ----
    private var orbitAnim1:  ValueAnimator? = null
    private var orbitAnim2:  ValueAnimator? = null
    private var breathAnim:  ValueAnimator? = null
    private var spinAnim:    ValueAnimator? = null
    private var scaleAnim:   ValueAnimator? = null
    private var rippleAnim1: AnimatorSet?   = null
    private var rippleAnim2: AnimatorSet?   = null
    private var rippleAnim3: AnimatorSet?   = null

    // ---- Geometry (computed onSizeChanged) ----
    private var cx = 0f; private var cy = 0f
    private var r = 0f          // half of view size (button radius = r*0.48)
    private val arcRect1 = RectF()
    private val arcRect2 = RectF()

    // ---- Click listener ----
    var onConnectClick: (() -> Unit)? = null

    init {
        setOnClickListener { onConnectClick?.invoke() }
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f; cy = h / 2f
        r = min(w, h) / 2f

        val arcStroke = r * 0.04f
        val orbitR1 = r * 0.95f
        arcRect1.set(cx - orbitR1, cy - orbitR1, cx + orbitR1, cy + orbitR1)

        val orbitR2 = r * 0.82f
        arcRect2.set(cx - orbitR2, cy - orbitR2, cx + orbitR2, cy + orbitR2)
    }

    // ================== DRAWING ==================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val isConnected  = vpnState == VpnState.CONNECTED
        val isConnecting = vpnState == VpnState.CONNECTING

        val glowColor = when {
            isConnected  -> colorSuccess
            isConnecting -> colorAccent
            else         -> colorGradientEnd
        }

        // 1. Expanding ripple rings (connected / connecting)
        if (isConnected || isConnecting) {
            paintRing.strokeWidth = r * 0.02f
            drawRippleRing(canvas, glowColor, ringScale1, ringAlpha1 * 0.6f)
            paintRing.strokeWidth = r * 0.015f
            drawRippleRing(canvas, glowColor, ringScale2, ringAlpha2 * 0.4f)
            paintRing.strokeWidth = r * 0.01f
            drawRippleRing(canvas, glowColor, ringScale3, ringAlpha3 * 0.3f)
        }

        // 2. Breathing glow
        val glowAlpha = when {
            isConnected  -> breathAlpha * 0.5f
            isConnecting -> breathAlpha * 0.7f
            else         -> breathAlpha * 0.2f
        }
        paintFill.shader = RadialGradient(
            cx, cy, r * 0.7f,
            intArrayOf(
                applyAlpha(glowColor, glowAlpha),
                applyAlpha(glowColor, glowAlpha * 0.3f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 0.7f, paintFill)
        paintFill.shader = null

        // 3. Pulsing arc track (170dp equiv = r * 0.85)
        val arcR      = r * 0.85f
        val arcStroke = r * 0.04f
        paintArc.strokeWidth = arcStroke
        val rect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        paintArc.color = applyAlpha(colorTrack, 0.5f)
        canvas.drawArc(rect, 0f, 360f, false, paintArc)

        if (isConnected) {
            paintArc.shader = SweepGradient(cx, cy,
                intArrayOf(colorGradientStart, colorGradientEnd, colorGradientStart), null)
            canvas.drawArc(rect, -90f, 360f, false, paintArc)
            paintArc.shader = null
        } else if (isConnecting) {
            paintArc.shader = SweepGradient(cx, cy,
                intArrayOf(Color.TRANSPARENT, colorAccent, Color.TRANSPARENT), null)
            canvas.drawArc(rect, connectSpin, 150f, false, paintArc)
            paintArc.shader = null
        }

        // 4. Orbit dots ring 1 (fast, 3 dots), only when connected/connecting
        if (isConnected || isConnecting) {
            val dotR1 = r * 0.95f
            drawOrbitDots(canvas, dotR1, orbitAngle1, 3, glowColor)
        }

        // 5. Orbit dots ring 2 (slow reverse, 2 dots), only when connected
        if (isConnected) {
            val dotR2 = r * 0.82f
            drawOrbitDots(canvas, dotR2, orbitAngle2, 2, colorGradientStart, dotSizeMultiplier = 0.5f)
        }

        // 6. Main gradient circle button
        val btnR = r * 0.57f * buttonScale
        val brushColors = when {
            isConnected  -> intArrayOf(colorSuccess, Color.parseColor("#00C853"))
            isConnecting -> intArrayOf(colorAccent, colorGradientEnd)
            else         -> intArrayOf(colorAccent, colorGradientEnd)
        }
        paintFill.shader = LinearGradient(
            cx - btnR, cy - btnR, cx + btnR, cy + btnR,
            brushColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, btnR, paintFill)
        paintFill.shader = null

        // Inner highlight
        paintFill.shader = RadialGradient(
            cx - btnR * 0.3f, cy - btnR * 0.3f, btnR * 0.6f,
            intArrayOf(applyAlpha(Color.WHITE, 0.25f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, btnR, paintFill)
        paintFill.shader = null

        // 7. VPN key icon (vector drawable, centered on button)
        drawVpnIcon(canvas, btnR)
    }

    private fun drawRippleRing(canvas: Canvas, color: Int, scale: Float, alpha: Float) {
        val ringBase = r * 0.65f
        paintRing.color = applyAlpha(color, alpha)
        canvas.drawCircle(cx, cy, ringBase * scale, paintRing)
    }

    private fun drawOrbitDots(
        canvas: Canvas, orbitRadius: Float, startAngle: Float, count: Int,
        color: Int, dotSizeMultiplier: Float = 1f
    ) {
        val step = 360f / count
        for (i in 0 until count) {
            val angle = Math.toRadians((startAngle + i * step).toDouble())
            val dx = (orbitRadius * cos(angle)).toFloat()
            val dy = (orbitRadius * sin(angle)).toFloat()
            val dotSize = r * 0.025f * dotSizeMultiplier
            val dotAlpha = if (i == 0) 0.9f else 0.45f
            paintDot.color = applyAlpha(color, dotAlpha)
            canvas.drawCircle(cx + dx, cy + dy, dotSize * if (i == 0) 1.5f else 1f, paintDot)
        }
    }

    private fun drawVpnIcon(canvas: Canvas, btnR: Float) {
        val d = vpnKeyDrawable ?: return
        // Icon size = 55% of button diameter
        val iconSize = (btnR * 1.1f).toInt()
        val half = iconSize / 2
        val left   = (cx - half).toInt()
        val top    = (cy - half).toInt()
        val right  = (cx + half).toInt()
        val bottom = (cy + half).toInt()
        d.setBounds(left, top, right, bottom)
        d.setTint(Color.WHITE)
        d.alpha = 230
        canvas.save()
        d.draw(canvas)
        canvas.restore()
    }

    // ================== STATE MACHINE ==================

    private fun onStateChanged(old: VpnState, new: VpnState) {
        stopAllAnimators()
        when (new) {
            VpnState.CONNECTING  -> startConnectingAnimations()
            VpnState.CONNECTED   -> startConnectedAnimations()
            VpnState.DISCONNECTED, VpnState.ERROR -> {
                buttonScale = 1f; breathAlpha = 0.15f; orbitAngle1 = 0f; orbitAngle2 = 0f
                resetRipples()
                invalidate()
            }
            VpnState.DISCONNECTING -> startDisconnectingAnimations()
        }
    }

    private fun stopAllAnimators() {
        listOf(orbitAnim1, orbitAnim2, breathAnim, spinAnim, scaleAnim).forEach { it?.cancel() }
        listOf(rippleAnim1, rippleAnim2, rippleAnim3).forEach { it?.cancel() }
    }

    private fun startConnectingAnimations() {
        // Connecting spinner arc
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1500; interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { connectSpin = it.animatedValue as Float; invalidate() }
            start()
        }
        // Breathing glow
        breathAnim = ValueAnimator.ofFloat(0.1f, 0.8f).apply {
            duration = 1200; repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { breathAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        // Scale pulse
        scaleAnim = ValueAnimator.ofFloat(1f, 0.9f, 1f).apply {
            duration = 1500; repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { buttonScale = it.animatedValue as Float; invalidate() }
            start()
        }
        // Slow orbit
        orbitAnim1 = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000; interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { orbitAngle1 = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startConnectedAnimations() {
        connectSpin = 0f; buttonScale = 1f

        // Orbit dots ring 1 (fast, 8s)
        orbitAnim1 = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000; interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { orbitAngle1 = it.animatedValue as Float; invalidate() }
            start()
        }
        // Orbit dots ring 2 (slow reverse, 12s)
        orbitAnim2 = ValueAnimator.ofFloat(360f, 0f).apply {
            duration = 12000; interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { orbitAngle2 = it.animatedValue as Float; invalidate() }
            start()
        }
        // Breathing glow
        breathAnim = ValueAnimator.ofFloat(0.15f, 0.6f).apply {
            duration = 2500; repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { breathAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        // Ripple rings (staggered 1s each)
        startRippleLoop(0)
        startRippleLoop(1)
        startRippleLoop(2)
    }

    private fun startDisconnectingAnimations() {
        breathAnim = ValueAnimator.ofFloat(0.6f, 0.1f).apply {
            duration = 600
            addUpdateListener { breathAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        scaleAnim = ValueAnimator.ofFloat(1f, 0.9f).apply {
            duration = 400
            addUpdateListener { buttonScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun resetRipples() {
        ringAlpha1 = 0f; ringScale1 = 0.6f
        ringAlpha2 = 0f; ringScale2 = 0.6f
        ringAlpha3 = 0f; ringScale3 = 0.6f
    }

    /** Infinite looping ripple for ring [idx] with staggered start */
    private fun startRippleLoop(idx: Int) {
        val delay = idx * 1000L
        postDelayed({ if (vpnState == VpnState.CONNECTED) runRipple(idx) }, delay)
    }

    private fun runRipple(idx: Int) {
        if (vpnState != VpnState.CONNECTED) return
        val scaleAnim = ValueAnimator.ofFloat(0.6f, 1.2f).apply { duration = 3000 }
        val alphaAnim  = ValueAnimator.ofFloat(0.5f, 0f).apply { duration = 3000 }
        scaleAnim.interpolator = AccelerateDecelerateInterpolator()
        alphaAnim.interpolator = AccelerateDecelerateInterpolator()

        scaleAnim.addUpdateListener {
            val v = it.animatedValue as Float
            when (idx) { 0 -> ringScale1 = v; 1 -> ringScale2 = v; 2 -> ringScale3 = v }
            invalidate()
        }
        alphaAnim.addUpdateListener {
            val v = it.animatedValue as Float
            when (idx) { 0 -> ringAlpha1 = v; 1 -> ringAlpha2 = v; 2 -> ringAlpha3 = v }
        }

        val set = AnimatorSet().apply {
            playTogether(scaleAnim, alphaAnim)
            doOnEnd { if (vpnState == VpnState.CONNECTED) runRipple(idx) }
        }
        when (idx) { 0 -> rippleAnim1 = set; 1 -> rippleAnim2 = set; 2 -> rippleAnim3 = set }
        set.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimators()
    }

    // ---- Helpers ----
    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}