package com.shilapi.xcertplay

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.widget.AbsSeekBar
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat

/*
 * The View *TintList setters need API 21 (Switch thumb and track tints API 23). On Android 4.4 the
 * same look comes from tinting a wrapped copy of the drawable. Getters only report what the
 * platform can; the UI code only assigns.
 */

private fun Drawable.tinted(tint: ColorStateList): Drawable =
    DrawableCompat.wrap(mutate()).also { DrawableCompat.setTintList(it, tint) }

internal var Switch.thumbTintCompat: ColorStateList?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) thumbTintList else null
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) thumbTintList = value
        else if (value != null) thumbDrawable?.let { thumbDrawable = it.tinted(value) }
    }

internal var Switch.trackTintCompat: ColorStateList?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) trackTintList else null
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) trackTintList = value
        else if (value != null) trackDrawable?.let { trackDrawable = it.tinted(value) }
    }

/** Before API 21 the ON/OFF labels are hidden by emptying them. */
internal var Switch.showTextCompat: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) showText else !textOn.isNullOrEmpty()
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            showText = value
        } else if (!value) {
            textOn = ""
            textOff = ""
        }
    }

internal var AbsSeekBar.thumbTintCompat: ColorStateList?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) thumbTintList else null
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) thumbTintList = value
        else if (value != null) thumb?.let { thumb = it.tinted(value) }
    }

internal var AbsSeekBar.splitTrackCompat: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && splitTrack
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) splitTrack = value
    }

/** Tints only the progress layer, as progressTintList does, not the background track. */
internal var ProgressBar.progressTintCompat: ColorStateList?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) progressTintList else null
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressTintList = value
            return
        }
        value ?: return
        val drawable = progressDrawable?.mutate() ?: return
        val progress = (drawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.progress) ?: drawable
        @Suppress("DEPRECATION")
        progress.setColorFilter(value.defaultColor, PorterDuff.Mode.SRC_IN)
    }

internal var View.backgroundTintCompat: ColorStateList?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) backgroundTintList else null
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            backgroundTintList = value
        } else if (value != null) {
            background?.let { @Suppress("DEPRECATION") setBackgroundDrawable(it.tinted(value)) }
        }
    }

internal var CompoundButton.buttonTintCompat: ColorStateList?
    get() = CompoundButtonCompat.getButtonTintList(this)
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            buttonTintList = value
        } else if (value != null) {
            CompoundButtonCompat.getButtonDrawable(this)?.let { setButtonDrawable(it.tinted(value)) }
        }
    }

internal var ImageView.imageTintCompat: ColorStateList?
    get() = ImageViewCompat.getImageTintList(this)
    set(value) = ImageViewCompat.setImageTintList(this, value)

internal var TextView.letterSpacingCompat: Float
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing else 0f
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = value
    }

/** Material buttons raise on press through a state list animator (API 21); there is none before. */
internal fun View.clearStateListAnimator() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
}
