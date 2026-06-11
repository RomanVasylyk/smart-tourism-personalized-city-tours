package com.example.smarttourism.features.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import kotlin.math.roundToInt

internal fun createStartPointIcon(context: Context, label: String): Icon {
    val density = context.resources.displayMetrics.density
    val width = (StartPointIconWidthDp * density).roundToInt()
    val height = (StartPointIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 16f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(StartPointOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 16f * density,
            tipY = height - 2f * density,
            shoulderY = 29f * density,
            shoulderHalfWidth = 7.5f * density
        ),
        paint
    )

    paint.color = Color.parseColor(StartPointFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 12.5f * density,
            tipY = height - 7f * density,
            shoulderY = 26f * density,
            shoulderHalfWidth = 5.2f * density
        ),
        paint
    )

    paint.apply {
        color = Color.parseColor(StartPointTextColor)
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val baseline = circleY - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(label, centerX, baseline, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

internal fun createCurrentLocationIcon(context: Context): Icon {
    val density = context.resources.displayMetrics.density
    val width = (CurrentLocationIconWidthDp * density).roundToInt()
    val height = (CurrentLocationIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 17f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(CurrentLocationOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 18f * density,
            tipY = height - 2f * density,
            shoulderY = 31f * density,
            shoulderHalfWidth = 9f * density
        ),
        paint
    )

    paint.color = Color.parseColor(CurrentLocationFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 13f * density,
            tipY = height - 7f * density,
            shoulderY = 28f * density,
            shoulderHalfWidth = 6.5f * density
        ),
        paint
    )

    paint.color = Color.parseColor(CurrentLocationCenterColor)
    canvas.drawCircle(centerX, circleY, 5f * density, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

internal fun createVisitedRouteStopIcon(context: Context): Icon {
    val density = context.resources.displayMetrics.density
    val width = (VisitedStopIconWidthDp * density).roundToInt()
    val height = (VisitedStopIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 15f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(VisitedStopOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 16f * density,
            tipY = height - 2f * density,
            shoulderY = 28f * density,
            shoulderHalfWidth = 8f * density
        ),
        paint
    )

    paint.color = Color.parseColor(VisitedStopFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 12f * density,
            tipY = height - 7f * density,
            shoulderY = 25f * density,
            shoulderHalfWidth = 5.8f * density
        ),
        paint
    )

    paint.apply {
        color = Color.parseColor(VisitedStopCheckColor)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * density
    }
    canvas.drawPath(
        Path().apply {
            moveTo(centerX - 5f * density, circleY)
            lineTo(centerX - 1f * density, circleY + 4f * density)
            lineTo(centerX + 6f * density, circleY - 5f * density)
        },
        paint
    )

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

internal fun createSelectedPoiIcon(context: Context): Icon {
    val density = context.resources.displayMetrics.density
    val width = (SelectedPoiIconWidthDp * density).roundToInt()
    val height = (SelectedPoiIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 15f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(SelectedPoiOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 16f * density,
            tipY = height - 2f * density,
            shoulderY = 28f * density,
            shoulderHalfWidth = 8f * density
        ),
        paint
    )

    paint.color = Color.parseColor(SelectedPoiFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 12f * density,
            tipY = height - 7f * density,
            shoulderY = 25f * density,
            shoulderHalfWidth = 5.8f * density
        ),
        paint
    )

    paint.apply {
        color = Color.parseColor(SelectedPoiCheckColor)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * density
    }
    canvas.drawPath(
        Path().apply {
            moveTo(centerX - 5f * density, circleY)
            lineTo(centerX - 1f * density, circleY + 4f * density)
            lineTo(centerX + 6f * density, circleY - 5f * density)
        },
        paint
    )

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

internal fun createTransitLineLabelIcon(
    iconFactory: IconFactory,
    context: Context,
    label: String,
    colorKey: String?,
    stage: RoutePathStage
): Icon {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(label, 0, label.length, textBounds)

    val horizontalPadding = 9f * density
    val verticalPadding = 5f * density
    val width = (textBounds.width() + (horizontalPadding * 2)).roundToInt().coerceAtLeast((28f * density).roundToInt())
    val height = (textBounds.height() + (verticalPadding * 2)).roundToInt().coerceAtLeast((22f * density).roundToInt())
    val cornerRadius = 10f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(transitLineColor(colorKey, stage))
    }
    canvas.drawRoundRect(
        RectF(0f, 0f, width.toFloat(), height.toFloat()),
        cornerRadius,
        cornerRadius,
        backgroundPaint
    )

    val baseline = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(label, width / 2f, baseline, textPaint)
    return iconFactory.fromBitmap(bitmap)
}

private fun locationPinPath(
    centerX: Float,
    circleY: Float,
    circleRadius: Float,
    tipY: Float,
    shoulderY: Float,
    shoulderHalfWidth: Float
): Path =
    Path().apply {
        addCircle(centerX, circleY, circleRadius, Path.Direction.CW)
        moveTo(centerX - shoulderHalfWidth, shoulderY)
        lineTo(centerX + shoulderHalfWidth, shoulderY)
        lineTo(centerX, tipY)
        close()
    }
