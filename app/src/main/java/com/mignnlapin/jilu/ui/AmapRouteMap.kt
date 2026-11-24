package com.mignnlapin.jilu.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.mignnlapin.jilu.TrackPoint
import kotlin.math.max

private class MapOverlayState {
    var configured = false
    var polyline: Polyline? = null
    var startMarker: Marker? = null
    var endMarker: Marker? = null
    var lastRouteSize = 0
}

@Composable
fun AmapRouteMap(
    apiKey: String,
    route: List<TrackPoint>,
    tracking: Boolean,
    lineColor: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(apiKey) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapsInitializer.setApiKey(apiKey)
        TextureMapView(context).apply {
            onCreate(Bundle())
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    val overlayState = remember(mapView) { MapOverlayState() }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    ) { view ->
        val map = view.map
        if (!overlayState.configured) {
            map.uiSettings.isZoomControlsEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isScaleControlsEnabled = false
            overlayState.configured = true
        }

        if (route.isEmpty()) {
            overlayState.polyline?.remove()
            overlayState.polyline = null
            overlayState.startMarker?.remove()
            overlayState.startMarker = null
            overlayState.endMarker?.remove()
            overlayState.endMarker = null
            overlayState.lastRouteSize = 0
            return@AndroidView
        }

        val latLngs = route.map { LatLng(it.latitude, it.longitude) }
        val first = latLngs.first()
        val last = latLngs.last()

        if (overlayState.startMarker == null) {
            overlayState.startMarker = map.addMarker(
                MarkerOptions().position(first).title("起点")
            )
        }
        if (overlayState.endMarker == null) {
            overlayState.endMarker = map.addMarker(
                MarkerOptions().position(last).title(if (tracking) "当前位置" else "终点")
            )
        }
        overlayState.endMarker?.position = last

        if (overlayState.polyline == null) {
            overlayState.polyline = map.addPolyline(
                PolylineOptions()
                    .width(12f)
                    .color(lineColor)
                    .addAll(latLngs)
            )
        } else {
            overlayState.polyline?.setPoints(latLngs)
        }

        if (overlayState.lastRouteSize == 0) {
            fitRoute(map, latLngs)
        } else if (tracking && route.size > overlayState.lastRouteSize) {
            val zoom = max(map.cameraPosition?.zoom ?: 15f, 15f)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(last, zoom))
        }
        overlayState.lastRouteSize = route.size
    }
}

private fun fitRoute(map: AMap, points: List<LatLng>) {
    if (points.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
        return
    }
    runCatching {
        val builder = LatLngBounds.builder()
        points.forEach { builder.include(it) }
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
    }
}
